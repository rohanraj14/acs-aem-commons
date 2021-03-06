/*
 * Copyright 2016 Adobe.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.adobe.acs.commons.fam.impl;

import com.adobe.acs.commons.fam.ThrottledTaskRunner;
import com.adobe.acs.commons.fam.mbean.ThrottledTaskRunnerMBean;
import com.adobe.granite.jmx.annotation.AnnotatedStandardMBean;
import java.lang.management.ManagementFactory;
import java.util.Dictionary;
import java.util.List;
import java.util.concurrent.*;
import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.TabularDataSupport;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Properties;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Service;
import org.apache.sling.commons.osgi.PropertiesUtil;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(metatype = true, immediate = true, label = "ACS AEM Commons - Throttled Task Runner Service")
@Service(ThrottledTaskRunner.class)
@Properties({
    @Property(name = "jmx.objectname", value = "com.adobe.acs.commons.fam:type=Throttled Task Runner", propertyPrivate = true),
    @Property(name = "max.threads", label = "Max threads", description = "Default is 4, recommended not to exceed the number of CPU cores",value = "4"),
    @Property(name = "max.cpu", label = "Max cpu %", description = "Range is 0..1; -1 means disable this check", value = "0.85"),
    @Property(name = "max.heap", label = "Max heap %", description = "Range is 0..1; -1 means disable this check", value = "0.75"),
    @Property(name = "cooldown.wait.time", label = "Cooldown time", description="Time to wait for cpu/mem cooldown between checks", value = "100"),
    @Property(name = "task.timeout", label = "Watchdog time", description="Maximum time allowed (in ms) per action before it is interrupted forcefully.", value = "60000"),})
public class ThrottledTaskRunnerImpl extends AnnotatedStandardMBean implements ThrottledTaskRunner {

    private static final Logger LOG = LoggerFactory.getLogger(ThrottledTaskRunnerImpl.class);
    private int taskTimeout;
    private int cooldownWaitTime;
    private int maxThreads;
    private double maxCpu;
    private double maxHeap;
    private boolean isPaused;
    private final MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
    private ObjectName osBeanName;
    private ObjectName memBeanName;
    private ThreadPoolExecutor workerPool;
    private BlockingQueue<Runnable> workQueue;

    public ThrottledTaskRunnerImpl() throws NotCompliantMBeanException {
        super(ThrottledTaskRunnerMBean.class);
    }

    @Override
    public void scheduleWork(Runnable work) {
        TimedRunnable r = new TimedRunnable(work, this, taskTimeout, TimeUnit.MILLISECONDS);
        workerPool.submit(r);
    }

    RunningStatistic waitTime = new RunningStatistic("Queue wait time");
    RunningStatistic throttleTime = new RunningStatistic("Throttle time");
    RunningStatistic processingTime = new RunningStatistic("Processing time");

    @Override
    public void logCompletion(long created, long started, long executed, long finished, boolean successful, Throwable error) {
        waitTime.log(started - created);
        throttleTime.log(executed - started);
        processingTime.log(finished - executed);
    }

    @Override
    public void clearProcessingStatistics() {
        waitTime.reset();
        throttleTime.reset();
        processingTime.reset();
    }

    @Override
    public TabularDataSupport getStatistics() {
        try {
            TabularDataSupport stats = new TabularDataSupport(RunningStatistic.getStaticsTableType());
            stats.put(waitTime.getStatistics());
            stats.put(throttleTime.getStatistics());
            stats.put(processingTime.getStatistics());
            return stats;
        } catch (OpenDataException ex) {
            LOG.error("Error generating statistics", ex);
            return null;
        }
    }

    @Override
    public boolean isRunning() {
        return workerPool != null && !workerPool.isTerminating() && !workerPool.isTerminated();
    }

    @Override
    public long getActiveCount() {
        return workerPool.getActiveCount();
    }

    @Override
    public long getTaskCount() {
        return workerPool.getTaskCount();
    }

    @Override
    public long getCompletedTaskCount() {
        return workerPool.getCompletedTaskCount();
    }

    List<Runnable> resumeList = null;

    @Override
    public void pauseExecution() {
        if (isRunning()) {
            resumeList = workerPool.shutdownNow();
            isPaused = true;
        }
    }

    @Override
    public void resumeExecution() {
        if (!isRunning()) {
            initThreadPool();
            if (isPaused && resumeList != null) {
                for (Runnable task : resumeList) {
                    workerPool.execute(task);
                }
                resumeList.clear();
            }
            isPaused = false;
        }
    }

    @Override
    public void stopExecution() {
        workerPool.shutdownNow();
        isPaused = false;
        if (resumeList != null) {
            resumeList.clear();
        }
    }

    @Override
    public int getMaxThreads() {
        return maxThreads;
    }

    @Override
    public void waitForLowCpuAndLowMemory() throws InterruptedException {
        boolean tooHigh = true;
        try {
            while (tooHigh) {
                double cpuLevel = getCpuLevel();
                double heapUsage = getMemoryUsage();

                if ((maxCpu <= 0 || cpuLevel <= maxCpu)
                        && (maxHeap <= 0 || heapUsage <= maxHeap)) {
                    tooHigh = false;
                } else {
                    Thread.sleep(cooldownWaitTime);
                }
            }
        } catch (InstanceNotFoundException ex) {
            LOG.error("OS MBean Instance not found (should not ever happen)", ex);
        } catch (ReflectionException ex) {
            LOG.error("OS MBean Instance reflection error (should not ever happen)", ex);
        }
    }

    private double getCpuLevel() throws InstanceNotFoundException, ReflectionException {
        // This method will block until CPU usage is low enough            
        AttributeList list = mbs.getAttributes(osBeanName, new String[]{"ProcessCpuLoad"});

        if (list.isEmpty()) {
            LOG.error("No CPU stats found for ProcessCpuLoad");
            return -1;
        }

        Attribute att = (Attribute) list.get(0);
        return (Double) att.getValue();
    }

    private double getMemoryUsage() {
        try {
            Object memoryusage = mbs.getAttribute(memBeanName, "HeapMemoryUsage");
            CompositeData cd = (CompositeData) memoryusage;
            long max = (Long) cd.get("max");
            long used = (Long) cd.get("used");
            return (double) used / (double) max;
        } catch (Exception e) {
            LOG.error("No Memory stats found for HeapMemoryUsage", e);
            return -1;
        }
    }

    @Override
    public void setThreadPoolSize(int newSize) {
        maxThreads = newSize;
        initThreadPool();
    }
    
    private void initThreadPool() {
        if (workQueue == null) {
            workQueue = new LinkedBlockingDeque<Runnable>();
        }

        // Terminate pool if the thread size has changed
        if (workerPool != null && workerPool.getMaximumPoolSize() != maxThreads) {
            try {
                workerPool.awaitTermination(taskTimeout, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                LOG.error("Timeout occurred when waiting to terminate worker pool", ex);
            }
            workerPool = null;
        }
        if (!isRunning()) {
            workerPool = new ThreadPoolExecutor(maxThreads, maxThreads, taskTimeout, TimeUnit.MILLISECONDS, workQueue);
        }
    }

    protected void activate(ComponentContext componentContext) {
        Dictionary<?, ?> properties = componentContext.getProperties();

        maxCpu = PropertiesUtil.toDouble(properties.get("max.cpu"), 0.85);
        maxHeap = PropertiesUtil.toDouble(properties.get("max.heap"), 0.85);
        maxThreads = PropertiesUtil.toInteger(properties.get("max.threads"), 4);
        cooldownWaitTime = PropertiesUtil.toInteger(properties.get("cooldown.wait.time"), 100);
        taskTimeout = PropertiesUtil.toInteger(properties.get("task.timeout"), 60000);

        try {
            memBeanName = ObjectName.getInstance("java.lang:type=Memory");
            osBeanName = ObjectName.getInstance("java.lang:type=OperatingSystem");
        } catch (MalformedObjectNameException ex) {
            LOG.error("Error getting OS MBean (shouldn't ever happen)", ex);
        } catch (NullPointerException ex) {
            LOG.error("Error getting OS MBean (shouldn't ever happen)", ex);
        }

        initThreadPool();
    }
}
