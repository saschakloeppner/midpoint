package com.evolveum.midpoint.wf.processors;

import com.evolveum.midpoint.util.logging.Trace;
import com.evolveum.midpoint.util.logging.TraceManager;
import org.apache.commons.configuration.Configuration;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.BeanNameAware;

/**
 * @author mederly
 */
public abstract class BaseChangeProcessor implements ChangeProcessor, BeanNameAware, BeanFactoryAware {

    private static final Trace LOGGER = TraceManager.getTrace(BaseChangeProcessor.class);

    private Configuration processorConfiguration;

    private String beanName;
    private BeanFactory beanFactory;

    private boolean enabled = false;

    public String getBeanName() {
        return beanName;
    }

    @Override
    public void setBeanName(String name) {
        this.beanName = name;
    }

    public BeanFactory getBeanFactory() {
        return beanFactory;
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Configuration getProcessorConfiguration() {
        return processorConfiguration;
    }

    protected void setProcessorConfiguration(Configuration c) {
        processorConfiguration = c;
    }
}
