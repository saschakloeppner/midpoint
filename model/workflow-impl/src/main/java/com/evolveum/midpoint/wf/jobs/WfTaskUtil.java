/*
 * Copyright (c) 2010-2013 Evolveum
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.evolveum.midpoint.wf.jobs;

import com.evolveum.midpoint.model.api.context.ModelContext;
import com.evolveum.midpoint.model.lens.LensContext;
import com.evolveum.midpoint.prism.*;
import com.evolveum.midpoint.prism.delta.ItemDelta;
import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.prism.delta.PropertyDelta;
import com.evolveum.midpoint.provisioning.api.ProvisioningService;
import com.evolveum.midpoint.repo.api.RepositoryService;
import com.evolveum.midpoint.schema.DeltaConvertor;
import com.evolveum.midpoint.schema.constants.SchemaConstants;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.task.api.Task;
import com.evolveum.midpoint.util.exception.*;
import com.evolveum.midpoint.util.logging.LoggingUtils;
import com.evolveum.midpoint.util.logging.Trace;
import com.evolveum.midpoint.util.logging.TraceManager;
import com.evolveum.midpoint.wf.WfConfiguration;
import com.evolveum.midpoint.wf.api.WfTaskExtensionItemsNames;
import com.evolveum.midpoint.wf.processors.primary.PrimaryApprovalProcessWrapper;
import com.evolveum.midpoint.wf.processors.ChangeProcessor;
import com.evolveum.midpoint.xml.ns._public.common.common_2a.*;
import com.evolveum.midpoint.xml.ns._public.communication.workflow_1.WfProcessInstanceEventType;
import com.evolveum.midpoint.xml.ns._public.communication.workflow_1.WfProcessVariable;
import com.evolveum.midpoint.xml.ns._public.model.model_context_2.LensContextType;
import com.evolveum.prism.xml.ns._public.types_2.ObjectDeltaType;
import com.evolveum.prism.xml.ns._public.types_2.PolyStringType;
import org.apache.commons.lang.Validate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.xml.namespace.QName;
import java.util.*;

/**
 * Handles low-level task operations, e.g. handling wf* properties in task extension.
 *
 * @author mederly
 *
 */

@Component
@DependsOn({ "prismContext" })

public class WfTaskUtil {

    @Autowired(required = true)
    private RepositoryService repositoryService;

    @Autowired(required = true)
    private PrismContext prismContext;

    @Autowired(required = true)
    private ProvisioningService provisioningService;

    @Autowired
    private WfConfiguration wfConfiguration;

	private static final Trace LOGGER = TraceManager.getTrace(WfTaskUtil.class);

    // wfModelContext - records current model context (i.e. context of current model operation)

    private PrismContainerDefinition wfModelContextContainerDefinition;
    private PrismPropertyDefinition wfSkipModelContextProcessingPropertyDefinition;

    // workflow-related extension properties
    private PrismPropertyDefinition wfStatusPropertyDefinition;
    private PrismPropertyDefinition wfLastDetailsPropertyDefinition;
    private PrismPropertyDefinition wfLastVariablesPropertyDefinition;
    private PrismPropertyDefinition wfProcessWrapperPropertyDefinition;
    private PrismPropertyDefinition wfChangeProcessorPropertyDefinition;
    private PrismPropertyDefinition wfProcessIdPropertyDefinition;
    private PrismPropertyDefinition wfDeltaToProcessPropertyDefinition;
    private PrismPropertyDefinition wfResultingDeltaPropertyDefinition;
    private PrismPropertyDefinition wfProcessInstanceFinishedPropertyDefinition;
    private PrismPropertyDefinition wfRootTaskOidPropertyDefinition;
    private PrismReferenceDefinition wfApprovedByReferenceDefinition;

    @PostConstruct
    public void init() {

        wfModelContextContainerDefinition = prismContext.getSchemaRegistry().findContainerDefinitionByElementName(SchemaConstants.MODEL_CONTEXT_NAME);
        wfSkipModelContextProcessingPropertyDefinition = prismContext.getSchemaRegistry().findPropertyDefinitionByElementName(SchemaConstants.SKIP_MODEL_CONTEXT_PROCESSING_PROPERTY);
        wfStatusPropertyDefinition = prismContext.getSchemaRegistry().findPropertyDefinitionByElementName(WfTaskExtensionItemsNames.WFSTATUS_PROPERTY_NAME);
        wfLastDetailsPropertyDefinition = prismContext.getSchemaRegistry().findPropertyDefinitionByElementName(WfTaskExtensionItemsNames.WFLAST_DETAILS_PROPERTY_NAME);
        wfLastVariablesPropertyDefinition = prismContext.getSchemaRegistry().findPropertyDefinitionByElementName(WfTaskExtensionItemsNames.WFLAST_VARIABLES_PROPERTY_NAME);
		wfProcessWrapperPropertyDefinition = prismContext.getSchemaRegistry().findPropertyDefinitionByElementName(WfTaskExtensionItemsNames.WFPROCESS_WRAPPER_PROPERTY_NAME);
        wfChangeProcessorPropertyDefinition = prismContext.getSchemaRegistry().findPropertyDefinitionByElementName(WfTaskExtensionItemsNames.WFCHANGE_PROCESSOR_PROPERTY_NAME);
		wfProcessIdPropertyDefinition = prismContext.getSchemaRegistry().findPropertyDefinitionByElementName(WfTaskExtensionItemsNames.WFPROCESSID_PROPERTY_NAME);
        wfDeltaToProcessPropertyDefinition = prismContext.getSchemaRegistry().findPropertyDefinitionByElementName(WfTaskExtensionItemsNames.WFDELTA_TO_PROCESS_PROPERTY_NAME);
        wfResultingDeltaPropertyDefinition = prismContext.getSchemaRegistry().findPropertyDefinitionByElementName(WfTaskExtensionItemsNames.WFRESULTING_DELTA_PROPERTY_NAME);
        wfProcessInstanceFinishedPropertyDefinition = prismContext.getSchemaRegistry().findPropertyDefinitionByElementName(WfTaskExtensionItemsNames.WFPROCESS_INSTANCE_FINISHED_PROPERTY_NAME);
        wfRootTaskOidPropertyDefinition = prismContext.getSchemaRegistry().findPropertyDefinitionByElementName(WfTaskExtensionItemsNames.WFROOT_TASK_OID_PROPERTY_NAME);
        wfApprovedByReferenceDefinition = prismContext.getSchemaRegistry().findReferenceDefinitionByElementName(WfTaskExtensionItemsNames.WFAPPROVED_BY_REFERENCE_NAME);

        Validate.notNull(wfModelContextContainerDefinition, SchemaConstants.MODEL_CONTEXT_NAME + " definition was not found");
        Validate.notNull(wfSkipModelContextProcessingPropertyDefinition, SchemaConstants.SKIP_MODEL_CONTEXT_PROCESSING_PROPERTY + " definition was not found");
        Validate.notNull(wfStatusPropertyDefinition, WfTaskExtensionItemsNames.WFSTATUS_PROPERTY_NAME + " definition was not found");
        Validate.notNull(wfLastDetailsPropertyDefinition, WfTaskExtensionItemsNames.WFLAST_DETAILS_PROPERTY_NAME + " definition was not found");
        Validate.notNull(wfLastVariablesPropertyDefinition, WfTaskExtensionItemsNames.WFLAST_VARIABLES_PROPERTY_NAME + " definition was not found");
        Validate.notNull(wfProcessWrapperPropertyDefinition, WfTaskExtensionItemsNames.WFPROCESS_WRAPPER_PROPERTY_NAME + " definition was not found");
        Validate.notNull(wfChangeProcessorPropertyDefinition, WfTaskExtensionItemsNames.WFCHANGE_PROCESSOR_PROPERTY_NAME + " definition was not found");
        Validate.notNull(wfProcessIdPropertyDefinition, WfTaskExtensionItemsNames.WFPROCESSID_PROPERTY_NAME + " definition was not found");
        Validate.notNull(wfDeltaToProcessPropertyDefinition, WfTaskExtensionItemsNames.WFDELTA_TO_PROCESS_PROPERTY_NAME + " definition was not found");
        Validate.notNull(wfResultingDeltaPropertyDefinition, WfTaskExtensionItemsNames.WFRESULTING_DELTA_PROPERTY_NAME + " definition was not found");
        Validate.notNull(wfProcessInstanceFinishedPropertyDefinition, WfTaskExtensionItemsNames.WFPROCESS_INSTANCE_FINISHED_PROPERTY_NAME + " definition was not found");
        Validate.notNull(wfRootTaskOidPropertyDefinition, WfTaskExtensionItemsNames.WFROOT_TASK_OID_PROPERTY_NAME + " definition was not found");
        Validate.notNull(wfApprovedByReferenceDefinition, WfTaskExtensionItemsNames.WFAPPROVED_BY_REFERENCE_NAME + " definition was not found");

        if (wfLastVariablesPropertyDefinition.isIndexed() != Boolean.FALSE) {
            throw new SystemException("lastVariables property isIndexed attribute is incorrect (should be FALSE, it is " + wfLastVariablesPropertyDefinition.isIndexed() + ")");
        }
        if (wfLastDetailsPropertyDefinition.isIndexed() != Boolean.FALSE) {
            throw new SystemException("lastDetails property isIndexed attribute is incorrect (should be FALSE, it is " + wfLastDetailsPropertyDefinition.isIndexed() + ")");
        }

    }

	void setWfProcessIdImmediate(Task task, String pid, OperationResult parentResult) throws SchemaException, ObjectNotFoundException {
		String oid = task.getOid();
		Validate.notEmpty(oid, "Task oid must not be null or empty (task must be persistent).");
		setExtensionPropertyImmediate(task, wfProcessIdPropertyDefinition, pid, parentResult);
	}

	String getLastVariables(Task task) {
		PrismProperty<?> p = task.getExtensionProperty(WfTaskExtensionItemsNames.WFLAST_VARIABLES_PROPERTY_NAME);
		if (p == null) {
			return null;
        } else {
			return p.getValue(String.class).getValue();
        }
	}

    String getLastDetails(Task task) {
        PrismProperty<?> p = task.getExtensionProperty(WfTaskExtensionItemsNames.WFLAST_DETAILS_PROPERTY_NAME);
        if (p == null) {
            return null;
        } else {
            return p.getValue(String.class).getValue();
        }
    }

    /**
	 * Sets an extension property.
	 */
	private<T> void setExtensionPropertyImmediate(Task task, PrismPropertyDefinition propertyDef, T propertyValue, OperationResult parentResult) throws SchemaException, ObjectNotFoundException {
		if (parentResult == null)
			parentResult = new OperationResult("setExtensionPropertyImmediate");
		
		PrismProperty prop = propertyDef.instantiate();
        prop.setValue(new PrismPropertyValue<T>(propertyValue));

		try {
            task.setExtensionPropertyImmediate(prop, parentResult);
        } catch (ObjectNotFoundException ex) {
			parentResult.recordFatalError("Object not found", ex);
            LoggingUtils.logException(LOGGER, "Cannot set {} for task {}", ex, propertyDef.getName(), task);
            throw ex;
		} catch (SchemaException ex) {
			parentResult.recordFatalError("Schema error", ex);
            LoggingUtils.logException(LOGGER, "Cannot set {} for task {}", ex, propertyDef.getName(), task);
            throw ex;
		} catch (RuntimeException ex) {
			parentResult.recordFatalError("Internal error", ex);
            LoggingUtils.logException(LOGGER, "Cannot set {} for task {}", ex, propertyDef.getName(), task);
            throw ex;
		}
		
	}

    private<T> void setExtensionProperty(Task task, PrismPropertyDefinition propertyDef, T propertyValue, OperationResult parentResult) throws SchemaException, ObjectNotFoundException {
        if (parentResult == null)
            parentResult = new OperationResult("setExtensionProperty");

        PrismProperty prop = propertyDef.instantiate();
        prop.setValue(new PrismPropertyValue<T>(propertyValue));

        try {
            task.setExtensionProperty(prop);
        } catch (SchemaException ex) {
            parentResult.recordFatalError("Schema error", ex);
            LoggingUtils.logException(LOGGER, "Cannot set {} for task {}", ex, propertyDef.getName(), task);
            throw ex;
        } catch (RuntimeException ex) {
            parentResult.recordFatalError("Internal error", ex);
            LoggingUtils.logException(LOGGER, "Cannot set {} for task {}", ex, propertyDef.getName(), task);
            throw ex;
        }

    }

    // todo: fix this brutal hack (task closing should not go through repo)
	void markTaskAsClosed(Task task, OperationResult parentResult) throws ObjectNotFoundException, SchemaException {
        String oid = task.getOid();
		try {
            ItemDelta delta = PropertyDelta.createReplaceDelta(
                    prismContext.getSchemaRegistry().findObjectDefinitionByCompileTimeClass(TaskType.class),
                    TaskType.F_EXECUTION_STATUS,
                    TaskExecutionStatusType.CLOSED);
            List<ItemDelta> deltas = new ArrayList<ItemDelta>();
            deltas.add(delta);
			repositoryService.modifyObject(TaskType.class, oid, deltas, parentResult);
		} catch (ObjectNotFoundException e) {
            LoggingUtils.logException(LOGGER, "Cannot mark task " + task + " as closed.", e);
            throw e;
        } catch (SchemaException e) {
            LoggingUtils.logException(LOGGER, "Cannot mark task " + task + " as closed.", e);
            throw e;
        } catch (ObjectAlreadyExistsException e) {
            LoggingUtils.logException(LOGGER, "Cannot mark task " + task + " as closed.", e);
            throw new SystemException(e);
        }

    }

//	void returnToAsyncCaller(Task task, OperationResult parentResult) throws Exception {
//		try {
//			task.finishHandler(parentResult);
////			if (task.getExclusivityStatus() == TaskExclusivityStatus.CLAIMED) {
////				taskManager.releaseTask(task, parentResult);	// necessary for active tasks
////				task.shutdown();								// this works when this java object is the same as is being executed by CycleRunner
////			}
////			if (task.getHandlerUri() != null)
////				task.setExecutionStatusWithPersist(TaskExecutionStatus.RUNNING, parentResult);
//		}
//		catch (Exception e) {
//			throw new Exception("Couldn't mark task " + task + " as running.", e);
//		}
//	}

//	void setTaskResult(String oid, OperationResult result) throws Exception {
//		try {
//			OperationResultType ort = result.createOperationResultType();
//			repositoryService.modifyObject(TaskType.class, ObjectTypeUtil.createModificationReplaceProperty(oid, 
//				SchemaConstants.C_RESULT,
//				ort), result);
//		}
//		catch (Exception e) {
//			throw new Exception("Couldn't set result for the task " + oid, e);
//		}
//
//	}


//    void putWorkflowAnswerIntoTask(boolean doit, Task task, OperationResult result) throws IOException, ClassNotFoundException {
//
//    	ModelOperationStateType state = task.getModelOperationState();
//    	if (state == null)
//    		state = new ModelOperationStateType();
//
////    	state.setShouldBeExecuted(doit);
////    	task.setModelOperationStateWithPersist(state, result);
//    }

    public void setProcessWrapper(Task task, PrimaryApprovalProcessWrapper wrapper) throws SchemaException {
        Validate.notNull(wrapper, "Process Wrapper is undefined.");
        PrismProperty<String> w = wfProcessWrapperPropertyDefinition.instantiate();
        w.setRealValue(wrapper.getClass().getName());
        task.setExtensionProperty(w);
    }

    public PrimaryApprovalProcessWrapper getProcessWrapper(Task task, List<PrimaryApprovalProcessWrapper> wrappers) {
        String wrapperClassName = getExtensionValue(String.class, task, WfTaskExtensionItemsNames.WFPROCESS_WRAPPER_PROPERTY_NAME);
        if (wrapperClassName == null) {
            throw new IllegalStateException("No wf process wrapper defined in task " + task);
        }

        for (PrimaryApprovalProcessWrapper w : wrappers) {
            if (wrapperClassName.equals(w.getClass().getName())) {
                return w;
            }
        }

        throw new IllegalStateException("Wrapper " + wrapperClassName + " is not registered.");
    }

    public void setChangeProcessor(Task task, ChangeProcessor processor) throws SchemaException {
        Validate.notNull(processor, "Change processor is undefined.");
        PrismProperty<String> w = wfChangeProcessorPropertyDefinition.instantiate();
        w.setRealValue(processor.getClass().getName());
        task.setExtensionProperty(w);
    }

    public ChangeProcessor getChangeProcessor(Task task) {
        String processorClassName = getExtensionValue(String.class, task, WfTaskExtensionItemsNames.WFCHANGE_PROCESSOR_PROPERTY_NAME);
        if (processorClassName == null) {
            throw new IllegalStateException("No change processor defined in task " + task);
        }
        return wfConfiguration.findChangeProcessor(processorClassName);
    }


    public Map<String, String> unwrapWfVariables(WfProcessInstanceEventType event) {
        Map<String,String> retval = new HashMap<String,String>();
        for (WfProcessVariable var : event.getWfProcessVariable()) {
            retval.put(var.getName(), var.getValue());
        }
        return retval;
    }

    public String getWfVariable(WfProcessInstanceEventType event, String name) {
        for (WfProcessVariable v : event.getWfProcessVariable())
            if (name.equals(v.getName()))
                return v.getValue();
        return null;
    }


//    public void markRejection(Task task, OperationResult result) throws ObjectNotFoundException, SchemaException {
//
//        ModelOperationStateType state = task.getModelOperationState();
//        state.setStage(null);
//        task.setModelOperationState(state);
//        try {
//            task.savePendingModifications(result);
//        } catch (ObjectAlreadyExistsException ex) {
//            throw new SystemException(ex);
//        }
//
//        markTaskAsClosed(task, result);         // brutal hack
//    }


//    public void markAcceptation(Task task, OperationResult result) throws ObjectNotFoundException, SchemaException {
//
//        ModelOperationStateType state = task.getModelOperationState();
//        ModelOperationStageType stage = state.getStage();
//        switch (state.getStage()) {
//            case PRIMARY: stage = ModelOperationStageType.SECONDARY; break;
//            case SECONDARY: stage = ModelOperationStageType.EXECUTE; break;
//            case EXECUTE: stage = null; break;      // should not occur
//            default: throw new SystemException("Unknown model operation stage: " + state.getStage());
//        }
//        state.setStage(stage);
//        task.setModelOperationState(state);
//        try {
//            task.savePendingModifications(result);
//        } catch (ObjectAlreadyExistsException ex) {
//            throw new SystemException(ex);
//        }
//
//        // todo continue with model operation
//
//        markTaskAsClosed(task, result);         // brutal hack
//
////        switch (state.getKindOfOperation()) {
////            case ADD: modelController.addObjectContinuing(task, result); break;
////            case MODIFY: modelController.modifyObjectContinuing(task, result); break;
////            case DELETE: modelController.deleteObjectContinuing(task, result); break;
////            default: throw new SystemException("Unknown kind of model operation: " + state.getKindOfOperation());
////        }
//
//    }

    public String getProcessId(Task task) {
        // let us request the current task status
        // todo make this property single-valued in schema to be able to use getRealValue
        PrismProperty idProp = task.getExtensionProperty(WfTaskExtensionItemsNames.WFPROCESSID_PROPERTY_NAME);
        Collection<String> values = null;
        if (idProp != null) {
            values = idProp.getRealValues(String.class);
        }
        if (values == null || values.isEmpty()) {
            return null;
        } else {
            return values.iterator().next();
        }
    }

//    void setModelOperationState(Task task, ModelContext context) throws IOException {
//        ModelOperationStateType state = new ModelOperationStateType();
//        state.setOperationData(SerializationUtil.toString(context));
//        task.setModelOperationState(state);
//    }

    public boolean hasModelContext(Task task) {
        PrismProperty modelContextProperty = task.getExtensionProperty(SchemaConstants.MODEL_CONTEXT_NAME);
        return modelContextProperty != null && modelContextProperty.getRealValue() != null;
    }

    public ModelContext retrieveModelContext(Task task, OperationResult result) throws SchemaException, ConfigurationException, ObjectNotFoundException, CommunicationException {
        PrismContainer<LensContextType> modelContextContainer = (PrismContainer) task.getExtensionItem(SchemaConstants.MODEL_CONTEXT_NAME);
        if (modelContextContainer == null || modelContextContainer.isEmpty()) {
            throw new SystemException("No model context information in task " + task);
        }
//        Object value = modelContextProperty.getRealValue();
//        if (value instanceof Element || value instanceof JAXBElement) {
//            value = prismContext.getPrismJaxbProcessor().unmarshalObject(value, LensContextType.class);
//        }
//        if (!(value instanceof LensContextType)) {
//            throw new SystemException("Model context information in task " + task + " is of wrong type: " + modelContextProperty.getRealValue().getClass());
//        }
        return LensContext.fromLensContextType(modelContextContainer.getValue().asContainerable(), prismContext, provisioningService, result);
    }

    public void storeModelContext(Task task, ModelContext context) throws SchemaException {
        Validate.notNull(context, "model context cannot be null");
        PrismContainer<LensContextType> modelContext = ((LensContext) context).toPrismContainer();
        storeModelContext(task, modelContext);
    }

    public void storeModelContext(Task task, PrismContainer<LensContextType> context) throws SchemaException {
        task.setExtensionContainer(context);
    }

    public void storeDeltasToProcess(List<ObjectDelta<? extends ObjectType>> deltas, Task task) throws SchemaException {

        PrismProperty<ObjectDeltaType> deltaToProcess = wfDeltaToProcessPropertyDefinition.instantiate();
        for (ObjectDelta<? extends ObjectType> delta : deltas) {
            deltaToProcess.addRealValue(DeltaConvertor.toObjectDeltaType(delta));
        }
        task.setExtensionProperty(deltaToProcess);
    }

    public void storeResultingDeltas(List<ObjectDelta<Objectable>> deltas, Task task) throws SchemaException {

        PrismProperty<ObjectDeltaType> resultingDeltas = wfResultingDeltaPropertyDefinition.instantiate();
        for (ObjectDelta delta : deltas) {
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("Storing delta into task {}; delta = {}", task, delta.debugDump(0));
            }
            resultingDeltas.addRealValue(DeltaConvertor.toObjectDeltaType(delta));
        }
        task.setExtensionProperty(resultingDeltas);

        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("Stored {} deltas into task {}", resultingDeltas.size(), task);
        }
    }


    public List<ObjectDelta<Objectable>> retrieveDeltasToProcess(Task task) throws SchemaException {

        PrismProperty<ObjectDeltaType> deltaTypePrismProperty = task.getExtensionProperty(WfTaskExtensionItemsNames.WFDELTA_TO_PROCESS_PROPERTY_NAME);
        if (deltaTypePrismProperty == null) {
            throw new SchemaException("No " + WfTaskExtensionItemsNames.WFDELTA_TO_PROCESS_PROPERTY_NAME + " in task extension; task = " + task);
        }
        List<ObjectDelta<Objectable>> retval = new ArrayList<ObjectDelta<Objectable>>();
        for (ObjectDeltaType objectDeltaType : deltaTypePrismProperty.getRealValues()) {
            retval.add(DeltaConvertor.createObjectDelta(objectDeltaType, prismContext));
        }
        return retval;
    }

    // it is possible that resulting deltas are not in the task
    public List<ObjectDelta<Objectable>> retrieveResultingDeltas(Task task) throws SchemaException {

        List<ObjectDelta<Objectable>> retval = new ArrayList<ObjectDelta<Objectable>>();

        PrismProperty<ObjectDeltaType> deltaTypePrismProperty = task.getExtensionProperty(WfTaskExtensionItemsNames.WFRESULTING_DELTA_PROPERTY_NAME);
        if (deltaTypePrismProperty != null) {
            for (ObjectDeltaType objectDeltaType : deltaTypePrismProperty.getRealValues()) {
                retval.add(DeltaConvertor.createObjectDelta(objectDeltaType, prismContext));
            }
        }
        return retval;
    }

    public void setTaskNameIfEmpty(Task t, PolyStringType taskName) {
        if (t.getName() == null || t.getName().toPolyString().isEmpty()) {
            t.setName(taskName);
        }
    }


    public void setWfLastVariables(Task task, String value) throws SchemaException {
        PrismProperty wfLastVariablesProperty = wfLastVariablesPropertyDefinition.instantiate();
        wfLastVariablesProperty.setValue(new PrismPropertyValue<String>(value));
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("WfLastVariable INDEXED = " + wfLastVariablesProperty.getDefinition().isIndexed());
        }
        task.setExtensionProperty(wfLastVariablesProperty);

    }

    public void addWfStatus(Task task, String value) throws SchemaException {
        PrismProperty wfStatusProperty = wfStatusPropertyDefinition.instantiate();
        PrismPropertyValue<String> newValue = new PrismPropertyValue<String>(value);
        wfStatusProperty.addValue(newValue);
        task.addExtensionProperty(wfStatusProperty);
    }

    private<T> T getExtensionValue(Class<T> clazz, Task task, QName propertyName) {
        PrismProperty<String> property = task.getExtensionProperty(propertyName);
        return property != null ? property.getRealValue(clazz) : null;
    }

    public void setProcessInstanceFinishedImmediate(Task task, Boolean value, OperationResult result) throws SchemaException, ObjectNotFoundException {
        setExtensionPropertyImmediate(task, wfProcessInstanceFinishedPropertyDefinition, value, result);
    }

    public boolean isProcessInstanceFinished(Task task) {
        Boolean value = getExtensionValue(Boolean.class, task, WfTaskExtensionItemsNames.WFPROCESS_INSTANCE_FINISHED_PROPERTY_NAME);
        return value != null ? value : false;
    }

    public void setRootTaskOidImmediate(Task task, String value, OperationResult result) throws SchemaException, ObjectNotFoundException {
        setExtensionPropertyImmediate(task, wfRootTaskOidPropertyDefinition, value, result);
    }

    public String getRootTaskOid(Task task) {
        return getExtensionValue(String.class, task, WfTaskExtensionItemsNames.WFROOT_TASK_OID_PROPERTY_NAME);
    }

    public void setSkipModelContextProcessingProperty(Task task, boolean value, OperationResult result) throws SchemaException, ObjectNotFoundException {
        setExtensionProperty(task, wfSkipModelContextProcessingPropertyDefinition, value, result);
    }

    public void setLastDetails(Task task, String status) throws SchemaException {
        PrismProperty wfLastDetailsProperty = wfLastDetailsPropertyDefinition.instantiate();
        PrismPropertyValue<String> newValue = new PrismPropertyValue<String>(status);
        wfLastDetailsProperty.setValue(newValue);
        task.setExtensionProperty(wfLastDetailsProperty);
    }

    public void addApprovedBy(Task task, ObjectReferenceType referenceType) throws SchemaException {
        PrismReference wfApprovedBy = wfApprovedByReferenceDefinition.instantiate();
        PrismReferenceValue newValue = new PrismReferenceValue(referenceType.getOid());
        wfApprovedBy.add(newValue);
        task.addExtensionReference(wfApprovedBy);
    }

    public void addApprovedBy(Task task, Collection<ObjectReferenceType> referenceTypes) throws SchemaException {
        PrismReference wfApprovedBy = wfApprovedByReferenceDefinition.instantiate();
        for (ObjectReferenceType referenceType : referenceTypes) {
            PrismReferenceValue newValue = new PrismReferenceValue(referenceType.getOid());
            wfApprovedBy.add(newValue);
        }
        task.addExtensionReference(wfApprovedBy);
    }

    public void addApprovedBy(Task task, String oid) throws SchemaException {
        ObjectReferenceType objectReferenceType = new ObjectReferenceType();
        objectReferenceType.setOid(oid);
        addApprovedBy(task, objectReferenceType);
    }

    public PrismReference getApprovedBy(Task task) throws SchemaException {
        return task.getExtensionReference(WfTaskExtensionItemsNames.WFAPPROVED_BY_REFERENCE_NAME);
    }

    public List<? extends ObjectReferenceType> getApprovedByFromTaskTree(Task task, OperationResult result) throws SchemaException {
        Set<String> approvers = new HashSet<String>();

        List<Task> tasks = task.listSubtasksDeeply(result);
        tasks.add(task);
        for (Task aTask : tasks) {
            PrismReference approvedBy = getApprovedBy(aTask);
            if (approvedBy != null) {
                for (PrismReferenceValue referenceValue : approvedBy.getValues()) {
                    approvers.add(referenceValue.getOid());
                }
            }
        }

        List<ObjectReferenceType> retval = new ArrayList<ObjectReferenceType>(approvers.size());
        for (String oid : approvers) {
            ObjectReferenceType referenceType = new ObjectReferenceType();
            referenceType.setOid(oid);
            retval.add(referenceType);
        }
        return retval;
    }

    public PrismContainerDefinition getWfModelContextContainerDefinition() {
        return wfModelContextContainerDefinition;
    }

    public PrismPropertyDefinition getWfSkipModelContextProcessingPropertyDefinition() {
        return wfSkipModelContextProcessingPropertyDefinition;
    }

    public PrismPropertyDefinition getWfStatusPropertyDefinition() {
        return wfStatusPropertyDefinition;
    }

    public PrismPropertyDefinition getWfLastDetailsPropertyDefinition() {
        return wfLastDetailsPropertyDefinition;
    }

    public PrismPropertyDefinition getWfLastVariablesPropertyDefinition() {
        return wfLastVariablesPropertyDefinition;
    }

    public PrismPropertyDefinition getWfProcessWrapperPropertyDefinition() {
        return wfProcessWrapperPropertyDefinition;
    }

    public PrismPropertyDefinition getWfChangeProcessorPropertyDefinition() {
        return wfChangeProcessorPropertyDefinition;
    }

    public PrismPropertyDefinition getWfProcessIdPropertyDefinition() {
        return wfProcessIdPropertyDefinition;
    }

    public PrismPropertyDefinition getWfDeltaToProcessPropertyDefinition() {
        return wfDeltaToProcessPropertyDefinition;
    }

    public PrismPropertyDefinition getWfResultingDeltaPropertyDefinition() {
        return wfResultingDeltaPropertyDefinition;
    }

    public PrismPropertyDefinition getWfProcessInstanceFinishedPropertyDefinition() {
        return wfProcessInstanceFinishedPropertyDefinition;
    }

    public PrismReferenceDefinition getWfApprovedByReferenceDefinition() {
        return wfApprovedByReferenceDefinition;
    }
}
