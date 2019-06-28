package org.kie.server.services.prometheus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

import org.jbpm.executor.AsynchronousJobListener;
import org.jbpm.services.api.DeploymentEventListener;
import org.kie.api.event.rule.AgendaEventListener;
import org.kie.dmn.api.core.event.DMNRuntimeEventListener;
import org.kie.server.services.api.KieContainerInstance;
import org.optaplanner.core.impl.phase.event.PhaseLifecycleListener;

class PrometheusCustomMetricsSupport {

    private final Map<Class, List<?>> customMetricsInstances;

    private ServiceLoader<PrometheusMetricsProvider> loader;
    private PrometheusKieServerExtension extension;

    PrometheusCustomMetricsSupport(PrometheusKieServerExtension extension) {
        this.extension = extension;
        loader = ServiceLoader.load(PrometheusMetricsProvider.class);
        customMetricsInstances = new ConcurrentHashMap<>();
    }

    boolean hasCustomMetrics() {
        return loader.iterator().hasNext();
    }

    List<PrometheusMetricsProvider> customMetricsProviders() {
        List<PrometheusMetricsProvider> providers = new ArrayList<>();
        loader.forEach(p -> providers.add(p));
        return providers;
    }

    List<DMNRuntimeEventListener> getDMNRuntimeEventListener(KieContainerInstance kContainer) {
        if (!customMetricsInstances.containsKey(DMNRuntimeEventListener.class)) {
            List<DMNRuntimeEventListener> dmnRuntimeEventListeners = new ArrayList<>();

            //add default listener
            dmnRuntimeEventListeners.add(new PrometheusMetricsDMNListener(extension.getMetrics(), kContainer));

            //and customer listeners, if any
            loader.forEach(p -> {
                DMNRuntimeEventListener l = p.createDMNRuntimeEventListener(kContainer);
                if (l != null) {
                    dmnRuntimeEventListeners.add(l);
                }
            });
            customMetricsInstances.put(DMNRuntimeEventListener.class, dmnRuntimeEventListeners);
        }
        return (List<DMNRuntimeEventListener>) customMetricsInstances.get(DMNRuntimeEventListener.class);
    }

    List<AgendaEventListener> getAgendaEventListener(String kieSessionId, KieContainerInstance kContainer) {
        if (!customMetricsInstances.containsKey(AgendaEventListener.class)) {
            List<AgendaEventListener> agendaEventListeners = new ArrayList<>();

            // add default listener
            agendaEventListeners.add(new PrometheusMetricsDroolsListener(extension.getMetrics(),
                                                                         kieSessionId,
                                                                         kContainer));
            // and customs listeners, if any
            loader.forEach(p -> {
                AgendaEventListener l = p.createAgendaEventListener(kieSessionId, kContainer);
                if (l != null) {
                    agendaEventListeners.add(l);
                }
            });
            customMetricsInstances.put(AgendaEventListener.class, agendaEventListeners);
        }
        return (List<AgendaEventListener>) customMetricsInstances.get(AgendaEventListener.class);
    }

    List<PhaseLifecycleListener> getPhaseLifecycleListener(String solverId) {
        if (!customMetricsInstances.containsKey(PhaseLifecycleListener.class)) {
            List<PhaseLifecycleListener> customMetricsTargets = new ArrayList<>();

            // add default listener first
            customMetricsTargets.add(new PrometheusMetricsSolverListener(solverId));

            //and then custom, if any
            loader.forEach(p -> {
                PhaseLifecycleListener l = p.createPhaseLifecycleListener(solverId);
                if (l != null) {
                    customMetricsTargets.add(l);
                }
            });
            customMetricsInstances.put(PhaseLifecycleListener.class, customMetricsTargets);
        }
        return (List<PhaseLifecycleListener>) customMetricsInstances.get(PhaseLifecycleListener.class);
    }

    List<AsynchronousJobListener> getAsynchronousJobListener() {
        return getListener(AsynchronousJobListener.class);
    }

    List<DeploymentEventListener> getDeploymentEventListener() {
        return getListener(DeploymentEventListener.class);
    }

    private <T> List<T> getListener(Class<T> clazz) {
        if (!customMetricsInstances.containsKey(clazz)) {
            List<T> customMetricsTargets = new ArrayList<>();
            loader.forEach(p -> {
                T l = createListener(p, clazz);
                if (l != null) {
                    customMetricsTargets.add(l);
                }
            });
            customMetricsInstances.put(clazz, customMetricsTargets);
        }
        return (List<T>)customMetricsInstances.get(clazz);
    }

    private <T> T createListener(PrometheusMetricsProvider p , Class<T> clazz) {
        T l = null;
        if (DeploymentEventListener.class.isAssignableFrom(clazz)) {
            l = (T) p.createDeploymentEventListener();
        }
        if (AsynchronousJobListener.class.isAssignableFrom(clazz)) {
            l = (T) p.createAsynchronousJobListener();
        }
        return l;
    }

}
