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

package com.evolveum.midpoint.wf.impl.processes.itemApproval;

import com.evolveum.midpoint.prism.polystring.PolyString;
import com.evolveum.midpoint.security.api.MidPointPrincipal;
import com.evolveum.midpoint.security.api.SecurityUtil;
import com.evolveum.midpoint.util.exception.*;
import com.evolveum.midpoint.util.logging.Trace;
import com.evolveum.midpoint.util.logging.TraceManager;
import com.evolveum.midpoint.wf.impl.processes.common.ActivitiUtil;
import com.evolveum.midpoint.wf.impl.processes.common.CommonProcessVariableNames;
import com.evolveum.midpoint.wf.impl.processes.common.SpringApplicationContextHolder;
import com.evolveum.midpoint.wf.util.ApprovalUtils;
import com.evolveum.midpoint.xml.ns._public.common.common_3.LevelEvaluationStrategyType;
import org.activiti.engine.delegate.DelegateExecution;
import org.activiti.engine.delegate.JavaDelegate;
import org.apache.commons.lang.Validate;

import java.util.Date;
import java.util.List;

import static com.evolveum.midpoint.wf.impl.processes.common.SpringApplicationContextHolder.getActivitiInterface;

/**
 * @author mederly
 */
public class RecordIndividualDecision implements JavaDelegate {

    private static final Trace LOGGER = TraceManager.getTrace(RecordIndividualDecision.class);

    // TODO concurrent execution of this routine might cause corruption of decisionList and allDecisions process variables

    public void execute(DelegateExecution execution) {

		String taskOid = execution.getVariable(CommonProcessVariableNames.VARIABLE_MIDPOINT_TASK_OID, String.class);
		Validate.notNull(taskOid, CommonProcessVariableNames.VARIABLE_MIDPOINT_TASK_OID + " is null");

        List<Decision> decisionList = (List<Decision>) execution.getVariable(ProcessVariableNames.DECISIONS_IN_LEVEL);
        Validate.notNull(decisionList, "decisionList is null");

        List<Decision> allDecisions = (List<Decision>) execution.getVariable(ProcessVariableNames.ALL_DECISIONS);
        Validate.notNull(allDecisions, "allDecisions is null");

        ApprovalLevelImpl level = (ApprovalLevelImpl) execution.getVariable(ProcessVariableNames.LEVEL);
        Validate.notNull(level, "level is null");
        level.setPrismContext(SpringApplicationContextHolder.getPrismContext());

        Integer stageNumber = ActivitiUtil.getRequiredVariable(execution, CommonProcessVariableNames.VARIABLE_STAGE_NUMBER, Integer.class);

        boolean approved = ApprovalUtils.isApproved((String) execution.getVariable(CommonProcessVariableNames.FORM_FIELD_DECISION));
        String comment = (String) execution.getVariable(CommonProcessVariableNames.FORM_FIELD_COMMENT);

        Decision decision = new Decision();

        MidPointPrincipal user = null;
		try {
			user = SecurityUtil.getPrincipal();
			decision.setApproverName(PolyString.getOrig(user.getName()));
            decision.setApproverOid(user.getOid());
		} catch (SecurityViolationException e) {
			decision.setApproverName("?");
            decision.setApproverOid("?");
		}

        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("======================================== Recording individual decision of " + user);
        }

        decision.setApproved(approved);
        decision.setComment(comment == null ? "" : comment);
        decision.setDate(new Date());
		decision.setStageNumber(stageNumber);
		decision.setStageName(level.getName());
		decision.setStageDisplayName(level.getDisplayName());

        decisionList.add(decision);
        allDecisions.add(decision);

        // here we carry out level evaluation strategy

        LevelEvaluationStrategyType levelEvaluationStrategyType = level.getEvaluationStrategy();

        Boolean setLoopApprovesInLevelStop = null;
        if (levelEvaluationStrategyType == LevelEvaluationStrategyType.FIRST_DECIDES) {
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("Setting " + ProcessVariableNames.LOOP_APPROVERS_IN_LEVEL_STOP + " to true, because the level evaluation strategy is 'firstDecides'.");
            }
            setLoopApprovesInLevelStop = Boolean.TRUE;
        } else if ((levelEvaluationStrategyType == null || levelEvaluationStrategyType == LevelEvaluationStrategyType.ALL_MUST_AGREE) && !decision.isApproved()) {
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("Setting " + ProcessVariableNames.LOOP_APPROVERS_IN_LEVEL_STOP + " to true, because the level eval strategy is 'allMustApprove' and the decision was 'reject'.");
            }
            setLoopApprovesInLevelStop = Boolean.TRUE;
        }

        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("Logged decision '" + approved + "'");
            LOGGER.trace("Resulting decision list = " + decisionList);
            LOGGER.trace("All decisions = " + allDecisions);
        }

        execution.setVariable(ProcessVariableNames.DECISIONS_IN_LEVEL, decisionList);
        execution.setVariable(ProcessVariableNames.ALL_DECISIONS, allDecisions);
        if (setLoopApprovesInLevelStop != null) {
            execution.setVariable(ProcessVariableNames.LOOP_APPROVERS_IN_LEVEL_STOP, setLoopApprovesInLevelStop);
        }
        execution.setVariable(
                CommonProcessVariableNames.VARIABLE_WF_STATE, "User " + decision.getApproverName() + " decided to " + (decision.isApproved() ? "approve" : "refuse") + " the request.");

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Approval process instance {} (id {}), level {}: recording decision {}; level stops now: {}",
					execution.getVariable(CommonProcessVariableNames.VARIABLE_PROCESS_INSTANCE_NAME),
                    execution.getProcessInstanceId(),
                    level.getDebugName(), decision, setLoopApprovesInLevelStop);
        }

        MidpointUtil.recordDecisionInTask(decision, taskOid);
		getActivitiInterface().notifyMidpointAboutProcessEvent(execution);
    }

}
