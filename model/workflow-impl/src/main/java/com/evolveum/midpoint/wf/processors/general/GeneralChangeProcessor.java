package com.evolveum.midpoint.wf.processors.general;

import com.evolveum.midpoint.common.configuration.api.MidpointConfiguration;
import com.evolveum.midpoint.model.api.context.ModelContext;
import com.evolveum.midpoint.model.api.hooks.HookOperationMode;
import com.evolveum.midpoint.model.common.expression.Expression;
import com.evolveum.midpoint.model.common.expression.ExpressionEvaluationContext;
import com.evolveum.midpoint.model.common.expression.ExpressionFactory;
import com.evolveum.midpoint.model.lens.LensContext;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.PrismObjectDefinition;
import com.evolveum.midpoint.prism.PrismProperty;
import com.evolveum.midpoint.prism.PrismPropertyDefinition;
import com.evolveum.midpoint.prism.PrismPropertyValue;
import com.evolveum.midpoint.prism.delta.PrismValueDeltaSetTriple;
import com.evolveum.midpoint.schema.constants.SchemaConstants;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.task.api.Task;
import com.evolveum.midpoint.util.DOMUtil;
import com.evolveum.midpoint.util.exception.CommunicationException;
import com.evolveum.midpoint.util.exception.ConfigurationException;
import com.evolveum.midpoint.util.exception.ExpressionEvaluationException;
import com.evolveum.midpoint.util.exception.ObjectAlreadyExistsException;
import com.evolveum.midpoint.util.exception.ObjectNotFoundException;
import com.evolveum.midpoint.util.exception.SchemaException;
import com.evolveum.midpoint.util.exception.SystemException;
import com.evolveum.midpoint.util.logging.LoggingUtils;
import com.evolveum.midpoint.util.logging.Trace;
import com.evolveum.midpoint.util.logging.TraceManager;
import com.evolveum.midpoint.wf.jobs.Job;
import com.evolveum.midpoint.wf.jobs.JobController;
import com.evolveum.midpoint.wf.jobs.JobCreationInstruction;
import com.evolveum.midpoint.wf.activiti.ActivitiEngine;
import com.evolveum.midpoint.wf.jobs.WfTaskUtil;
import com.evolveum.midpoint.wf.messages.ProcessEvent;
import com.evolveum.midpoint.wf.processes.CommonProcessVariableNames;
import com.evolveum.midpoint.wf.processors.BaseChangeProcessor;
import com.evolveum.midpoint.wf.processors.BaseConfigurationHelper;
import com.evolveum.midpoint.wf.processors.BaseModelInvocationProcessingHelper;
import com.evolveum.midpoint.wf.util.JaxbValueContainer;
import com.evolveum.midpoint.wf.util.SerializationSafeContainer;
import com.evolveum.midpoint.xml.ns._public.common.common_2a.ExpressionType;
import com.evolveum.midpoint.xml.ns._public.common.common_2a.GeneralChangeProcessorConfigurationType;
import com.evolveum.midpoint.xml.ns._public.common.common_2a.GeneralChangeProcessorScenarioType;
import com.evolveum.midpoint.xml.ns._public.common.common_2a.GenericObjectType;
import com.evolveum.midpoint.xml.ns._public.common.common_2a.ObjectType;
import com.evolveum.midpoint.xml.ns._public.common.common_2a.WfProcessInstanceType;
import com.evolveum.midpoint.xml.ns._public.model.model_context_2.LensContextType;
import com.evolveum.prism.xml.ns._public.types_2.PolyStringType;

import org.activiti.engine.form.FormProperty;
import org.activiti.engine.form.TaskFormData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.xml.namespace.QName;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * @author mederly
 */
@Component
public class GeneralChangeProcessor extends BaseChangeProcessor {

    private static final Trace LOGGER = TraceManager.getTrace(GeneralChangeProcessor.class);

    @Autowired
    private PrismContext prismContext;

    @Autowired
    private WfTaskUtil wfTaskUtil;

    @Autowired
    private JobController jobController;

    @Autowired
    private ActivitiEngine activitiEngine;

    @Autowired
    private BaseModelInvocationProcessingHelper baseModelInvocationProcessingHelper;

    @Autowired
    private GeneralChangeProcessorConfigurationHelper myConfigurationHelper;

    private GeneralChangeProcessorConfigurationType processorConfigurationType;

    @PostConstruct
    public void init() {
        processorConfigurationType = myConfigurationHelper.configure(this);
        if (isEnabled()) {
            putStartupMessage();
        }
    }

    private void putStartupMessage() {
        int scenarios = processorConfigurationType.getScenario().size();
        if (scenarios > 0) {
            LOGGER.info(getBeanName() + " initialized correctly (number of scenarios: " + scenarios + ")");
        } else {
            LOGGER.warn(getBeanName() + " initialized correctly, but there are no scenarios - so it will never be invoked");
        }
    }

    @Override
    public HookOperationMode processModelInvocation(ModelContext context, Task taskFromModel, OperationResult result) throws SchemaException {

        if (processorConfigurationType.getScenario().isEmpty()) {
            LOGGER.warn("No scenarios for " + getBeanName());
        }

        for (GeneralChangeProcessorScenarioType scenarioType : processorConfigurationType.getScenario()) {
            if (Boolean.FALSE.equals(scenarioType.isEnabled())) {
                LOGGER.trace("scenario {} is disabled, skipping", scenarioType.getName());
            } else if (!evaluateActivationCondition(scenarioType, context, result)) {
                LOGGER.trace("activationCondition was evaluated to FALSE for scenario named {}", scenarioType.getName());
            } else {
                LOGGER.trace("Applying scenario {} (process name {})", scenarioType.getName(), scenarioType.getProcessName());
                return applyScenario(scenarioType, context, taskFromModel, result);
            }
        }
        LOGGER.trace("No scenario found to be applicable, exiting the change processor.");
        return HookOperationMode.FOREGROUND;
    }

    private HookOperationMode applyScenario(GeneralChangeProcessorScenarioType scenarioType, ModelContext context, Task taskFromModel, OperationResult result) {

        Throwable failReason;

        try {

            // ========== preparing root task ===========

            JobCreationInstruction rootInstruction = baseModelInvocationProcessingHelper.createInstructionForRoot(this, context, taskFromModel);
            Job rootJob = baseModelInvocationProcessingHelper.createRootJob(rootInstruction, taskFromModel, result);

            // ========== preparing child task, starting WF process ===========

            JobCreationInstruction instruction = JobCreationInstruction.createWfProcessChildJob(rootJob);
            instruction.setProcessDefinitionKey(scenarioType.getProcessName());
            instruction.setRequesterOidInProcess(taskFromModel.getOwner());
            instruction.setTaskName("Workflow-monitoring task");

            LensContextType lensContextType = ((LensContext<?>) context).toPrismContainer().getValue().asContainerable();
            instruction.addProcessVariable(CommonProcessVariableNames.VARIABLE_MODEL_CONTEXT, new JaxbValueContainer<LensContextType>(lensContextType, prismContext));

            jobController.createJob(instruction, rootJob, result);

            // ========== complete the action ===========

            baseModelInvocationProcessingHelper.logTasksBeforeStart(rootJob, result);
            rootJob.startWaitingForSubtasks(result);

            return HookOperationMode.BACKGROUND;

        } catch (SchemaException e) {
            failReason = e;
        } catch (ObjectNotFoundException e) {
            failReason = e;
        } catch (RuntimeException e) {
            failReason = e;
        } catch (CommunicationException e) {
            failReason = e;
        } catch (ConfigurationException e) {
            failReason = e;
        }

        LoggingUtils.logException(LOGGER, "Workflow process(es) could not be started", failReason);
        result.recordFatalError("Workflow process(es) could not be started: " + failReason, failReason);
        return HookOperationMode.ERROR;

        // todo rollback - at least close open tasks, maybe stop workflow process instances
    }

    private boolean evaluateActivationCondition(GeneralChangeProcessorScenarioType scenarioType, ModelContext context, OperationResult result) throws SchemaException {
        ExpressionType conditionExpression = scenarioType.getActivationCondition();

        if (conditionExpression == null) {
            return true;
        }

        Map<QName,Object> variables = new HashMap<QName,Object>();
        variables.put(new QName(SchemaConstants.NS_C, "context"), context);

        boolean start;
        try {
            start = evaluateBooleanExpression(conditionExpression, variables, "workflow activation condition", result);
        } catch (ObjectNotFoundException e) {
            throw new SystemException("Couldn't evaluate generalChangeProcessor activation condition", e);
        } catch (ExpressionEvaluationException e) {
            throw new SystemException("Couldn't evaluate generalChangeProcessor activation condition", e);
        }
        return start;
    }

    private ExpressionFactory expressionFactory;

    private ExpressionFactory getExpressionFactory() {
        LOGGER.trace("Getting expressionFactory");
        ExpressionFactory ef = getBeanFactory().getBean("expressionFactory", ExpressionFactory.class);
        if (ef == null) {
            throw new IllegalStateException("expressionFactory bean cannot be found");
        }
        return ef;
    }

    private boolean evaluateBooleanExpression(ExpressionType expressionType, Map<QName, Object> expressionVariables, String opContext, OperationResult result) throws ObjectNotFoundException, SchemaException, ExpressionEvaluationException {

        if (expressionFactory == null) {
            expressionFactory = getExpressionFactory();
        }

        PrismContext prismContext = expressionFactory.getPrismContext();
        QName resultName = new QName(SchemaConstants.NS_C, "result");
        PrismPropertyDefinition resultDef = new PrismPropertyDefinition(resultName, DOMUtil.XSD_BOOLEAN, prismContext);
        Expression<PrismPropertyValue<Boolean>> expression = expressionFactory.makeExpression(expressionType, resultDef, opContext, result);
        ExpressionEvaluationContext params = new ExpressionEvaluationContext(null, expressionVariables, opContext, result);
        PrismValueDeltaSetTriple<PrismPropertyValue<Boolean>> exprResultTriple = expression.evaluate(params);

        Collection<PrismPropertyValue<Boolean>> exprResult = exprResultTriple.getZeroSet();
        if (exprResult.size() == 0) {
            return false;
        } else if (exprResult.size() > 1) {
            throw new IllegalStateException("Expression should return exactly one boolean value; it returned " + exprResult.size() + " ones");
        }
        Boolean boolResult = exprResult.iterator().next().getValue();
        return boolResult != null ? boolResult : false;
    }


    @Override
    public void onProcessEnd(ProcessEvent event, Job job, OperationResult result) throws SchemaException, ObjectAlreadyExistsException, ObjectNotFoundException {

        Task task = job.getTask();
        // we simply put model context back into parent task
        // (or if it is null, we set the task to skip model context processing)

        // it is safe to directly access the parent, because (1) it is in waiting state, (2) we are its only child

        Task rootTask = task.getParentTask(result);

        SerializationSafeContainer <LensContextType> contextContainer = (SerializationSafeContainer<LensContextType>) event.getVariable(CommonProcessVariableNames.VARIABLE_MODEL_CONTEXT);
        LensContextType lensContextType = null;
        if (contextContainer != null) {
            contextContainer.setPrismContext(prismContext);
            lensContextType = contextContainer.getValue();
        }

        if (lensContextType == null) {
            LOGGER.debug(CommonProcessVariableNames.VARIABLE_MODEL_CONTEXT + " not present in process, this means we should stop processing. Task = {}", task);
            wfTaskUtil.setSkipModelContextProcessingProperty(rootTask, true, result);
        } else {
            LOGGER.debug("Putting (changed or unchanged) value of " + CommonProcessVariableNames.VARIABLE_MODEL_CONTEXT + " into the task {}", task);
            wfTaskUtil.storeModelContext(rootTask, lensContextType.asPrismContainerValue().getContainer());
        }

        rootTask.savePendingModifications(result);
        LOGGER.trace("onProcessEnd ending for task {}", task);
    }

    @Override
    public PrismObject<? extends ObjectType> getRequestSpecificData(org.activiti.engine.task.Task task, Map<String, Object> variables, OperationResult result) throws SchemaException, ObjectNotFoundException {
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("getRequestSpecific starting: execution id " + task.getExecutionId() + ", pid " + task.getProcessInstanceId() + ", variables = " + variables);
        }

        PrismObjectDefinition<GenericObjectType> prismDefinition = prismContext.getSchemaRegistry().findObjectDefinitionByType(GenericObjectType.COMPLEX_TYPE);
        PrismObject<GenericObjectType> prism = prismDefinition.instantiate();

        TaskFormData data = activitiEngine.getFormService().getTaskFormData(task.getId());
        for (FormProperty formProperty : data.getFormProperties()) {
            if (formProperty.isReadable() && !formProperty.getId().startsWith(CommonProcessVariableNames.FORM_BUTTON_PREFIX)) {
                LOGGER.trace("- processing property {} having value {}", formProperty.getId(), formProperty.getValue());
                if (formProperty.getValue() != null) {
                    QName propertyName = new QName(SchemaConstants.NS_WFCF, formProperty.getId());
                    PrismPropertyDefinition<String> prismPropertyDefinition = new PrismPropertyDefinition<String>(propertyName, DOMUtil.XSD_STRING, prismContext);
                    PrismProperty<String> prismProperty = prismPropertyDefinition.instantiate();
                    prismProperty.addRealValue(formProperty.getValue());
                    prism.add(prismProperty);
                }
            }
        }
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("Resulting prism object instance = " + prism.debugDump());
        }
        return prism;

    }

    @Override
    public PrismObject<? extends ObjectType> getRelatedObject(org.activiti.engine.task.Task task, Map<String, Object> variables, OperationResult result) throws SchemaException, ObjectNotFoundException {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public String getProcessInstanceDetailsPanelName(WfProcessInstanceType processInstance) {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    private GeneralChangeProcessorScenarioType findScenario(String scenarioName) {
        for (GeneralChangeProcessorScenarioType scenario : processorConfigurationType.getScenario()) {
            if (scenarioName.equals(scenario.getName())) {
                return scenario;
            }
        }
        throw new SystemException("Scenario named " + scenarioName + " couldn't be found");
    }

    public void disableScenario(String scenarioName) {
        findScenario(scenarioName).setEnabled(false);
    }

    public void enableScenario(String scenarioName) {
        findScenario(scenarioName).setEnabled(true);
    }

}
