/*
 * Copyright (c) 2012 Evolveum
 *
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License). You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the License at
 * http://www.opensource.org/licenses/cddl1 or
 * CDDLv1.0.txt file in the source code distribution.
 * See the License for the specific language governing
 * permission and limitations under the License.
 *
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 *
 * Portions Copyrighted 2012 [name of copyright owner]
 */

package com.evolveum.midpoint.model.synchronizer;

import com.evolveum.midpoint.common.refinery.RefinedResourceSchema;
import com.evolveum.midpoint.common.refinery.ResourceAccountType;
import com.evolveum.midpoint.model.AccountSyncContext;
import com.evolveum.midpoint.model.PolicyDecision;
import com.evolveum.midpoint.model.SyncContext;
import com.evolveum.midpoint.model.util.Utils;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.PrismObjectDefinition;
import com.evolveum.midpoint.prism.delta.ChangeType;
import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.prism.schema.PrismSchema;
import com.evolveum.midpoint.prism.schema.SchemaRegistry;
import com.evolveum.midpoint.provisioning.api.ProvisioningService;
import com.evolveum.midpoint.repo.api.RepositoryService;
import com.evolveum.midpoint.schema.constants.SchemaConstants;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.schema.util.ResourceObjectShadowUtil;
import com.evolveum.midpoint.util.exception.CommunicationException;
import com.evolveum.midpoint.util.exception.ConfigurationException;
import com.evolveum.midpoint.util.exception.ExpressionEvaluationException;
import com.evolveum.midpoint.util.exception.ObjectNotFoundException;
import com.evolveum.midpoint.util.exception.SchemaException;
import com.evolveum.midpoint.util.logging.Trace;
import com.evolveum.midpoint.util.logging.TraceManager;
import com.evolveum.midpoint.xml.ns._public.common.common_1.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * @author semancik
 */
@Component
public class UserSynchronizer {

    private static final Trace LOGGER = TraceManager.getTrace(UserSynchronizer.class);

    @Autowired(required = true)
    @Qualifier("cacheRepositoryService")
    private transient RepositoryService cacheRepositoryService;

    @Autowired(required = true)
    private ProvisioningService provisioningService;

    @Autowired(required = true)
    private UserPolicyProcessor userPolicyProcessor;

    @Autowired(required = true)
    private AssignmentProcessor assignmentProcessor;

    @Autowired(required = true)
    private InboundProcessor inboundProcessor;

    @Autowired(required = true)
    private OutboundProcessor outboundProcessor;

    @Autowired(required = true)
    private ReconciliationProcessor reconciliationProcessor;

    @Autowired(required = true)
    private ConsolidationProcessor consolidationProcessor;

    @Autowired(required = true)
    private CredentialsProcessor credentialsProcessor;

    @Autowired(required = true)
    private ActivationProcessor activationProcessor;

    @Autowired(required = true)
    private PrismContext prismContext;

    public void synchronizeUser(SyncContext context, OperationResult result) throws SchemaException,
            ObjectNotFoundException, ExpressionEvaluationException, CommunicationException, ConfigurationException {

        loadUser(context, result);
        loadFromSystemConfig(context, result);
        context.recomputeUserNew();

        loadAccountRefs(context, result);
        context.recomputeUserNew();

        // Check reconcile flag in account sync context and set accountOld
        // variable if it's not set (from provisioning)
        checkAccountContextReconciliation(context, result);

        traceContext("Context after LOAD and recompute:\n{}", context);

        // Loop through the account changes, apply inbound expressions
        inboundProcessor.processInbound(context, result);
        context.recomputeUserNew();
        traceContext("inbound", context);

        userPolicyProcessor.processUserPolicy(context, result);
        context.recomputeUserNew();
        traceContext("user policy", context);
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("User delta:\n{}", context.getUserDelta() == null ? "null" : context.getUserDelta().dump());
        }

        assignmentProcessor.processAssignments(context, result);
        context.recomputeNew();
        traceContext("assignments", context);

        outboundProcessor.processOutbound(context, result);
        context.recomputeNew();
        traceContext("outbound", context);

        consolidationProcessor.consolidateValues(context, result);
        context.recomputeNew();
        traceContext("consolidation", context);

        credentialsProcessor.processCredentials(context, result);
        context.recomputeNew();
        traceContext("credentials", context);

        activationProcessor.processActivation(context, result);
        context.recomputeNew();
        traceContext("activation", context);

        reconciliationProcessor.processReconciliation(context, result);
        context.recomputeNew();
        traceContext("reconciliation", context);

    }

    private void checkAccountContextReconciliation(SyncContext context, OperationResult result)
            throws ObjectNotFoundException, CommunicationException, SchemaException, ConfigurationException {

        OperationResult subResult = result.createSubresult(UserSynchronizer.class + ".checkAccountContextReconciliation");
        try {
            for (AccountSyncContext accContext : context.getAccountContexts()) {
                if (!accContext.isDoReconciliation() || accContext.getAccountOld() != null) {
                    continue;
                }

                AccountShadowType account = provisioningService.getObject(AccountShadowType.class, accContext.getOid(),
                        null, subResult).asObjectable();
                ResourceType resource = Utils.getResource(account, provisioningService, result);
                PrismObjectDefinition<AccountShadowType> definition = RefinedResourceSchema.getRefinedSchema(
                        resource, prismContext).getObjectDefinition(account);

                PrismObject<AccountShadowType> object = definition.instantiate(SchemaConstants.I_ACCOUNT);
                object.setOid(account.getOid());
                accContext.setAccountOld(object);
            }
        } finally {
            subResult.computeStatus();
        }
    }

    private void traceContext(String phase, SyncContext context) {
    	if (LOGGER.isDebugEnabled()) {
    		StringBuilder sb = new StringBuilder("After ");
    		sb.append(phase);
    		sb.append(":");
    		for (ObjectDelta objectDelta: context.getAllChanges()) {
    			sb.append("\n");
    			sb.append(objectDelta.toString());
    		}
    		LOGGER.debug(sb.toString());
    	}
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("Context after {} and recompute:\n{}", phase, context.dump());
        }
    }

    private void loadUser(SyncContext context, OperationResult result) throws SchemaException, ObjectNotFoundException {
        if (context.getUserOld() != null) {
            // already loaded
            return;
        }
        if (context.getUserDelta().getObjectToAdd() != null) {
            //we're adding user
            //todo it's only fast fix - how to check that we're adding user
            return;
        }

        ObjectDelta<UserType> userPrimaryDelta = context.getUserPrimaryDelta();
        if (userPrimaryDelta == null) {
            // no change to user
            // TODO: where to get OID from????
            throw new UnsupportedOperationException("TODO");
        }
        if (userPrimaryDelta.getChangeType() == ChangeType.ADD) {
            // null oldUser is OK
            return;
        }
        String userOid = userPrimaryDelta.getOid();

        PrismObject<UserType> user = cacheRepositoryService.getObject(UserType.class, userOid, null, result);
        context.setUserOld(user);
    }

    private void loadAccountRefs(SyncContext context, OperationResult result) throws ObjectNotFoundException,
            SchemaException, CommunicationException, ConfigurationException {
        PolicyDecision policyDecision = null;
        if (context.getUserPrimaryDelta() != null && context.getUserPrimaryDelta().getChangeType() == ChangeType.DELETE) {
            // If user is deleted, all accounts should also be deleted
            policyDecision = PolicyDecision.DELETE;
        }

        PrismObject<UserType> userNew = context.getUserNew();
        if (userNew != null) {
            UserType userTypeNew = userNew.asObjectable();
            loadAccountRefsFromUser(context, userTypeNew, policyDecision, result);
        }

        PrismObject<UserType> userOld = context.getUserOld();
        if (userOld != null) {
            // Accounts that are not in userNew but are in userOld are to be unlinked
            if (policyDecision == null) {
                policyDecision = PolicyDecision.UNLINK;
            }
            loadAccountRefsFromUser(context, userOld.asObjectable(), policyDecision, result);
        }

    }

    /**
     * Does not overwrite existing account contexts, just adds new ones.
     * @throws ConfigurationException 
     */
    private void loadAccountRefsFromUser(SyncContext context, UserType userType, PolicyDecision policyDecision,
            OperationResult result) throws ObjectNotFoundException, CommunicationException, SchemaException, ConfigurationException {
        for (ObjectReferenceType accountRef : userType.getAccountRef()) {
            String oid = accountRef.getOid();
            if (accountContextAlreadyExists(oid, context)) {
                continue;
            }
            // Fetching from repository instead of provisioning so we avoid reading in a full account
            AccountShadowType accountType = cacheRepositoryService.getObject(AccountShadowType.class, oid, null, result).asObjectable();
            String resourceOid = ResourceObjectShadowUtil.getResourceOid(accountType);
            ResourceAccountType rat = new ResourceAccountType(resourceOid, accountType.getAccountType());
            AccountSyncContext accountSyncContext = context.getAccountSyncContext(rat);
            if (accountSyncContext == null) {
                ResourceType resource = context.getResource(rat);
                if (resource == null) {
                    // Fetching from provisioning to take advantage of caching and pre-parsed schema
                    resource = provisioningService.getObject(ResourceType.class, resourceOid, null, result).asObjectable();
                    context.rememberResource(resource);
                }
                accountSyncContext = context.createAccountSyncContext(rat);
                if (accountSyncContext.getPolicyDecision() == null) {
                    accountSyncContext.setPolicyDecision(policyDecision);
                }
            }
            accountSyncContext.setOid(oid);
            if (context.isDoReconciliationForAllAccounts()) {
                accountSyncContext.setDoReconciliation(true);
            }
        }
    }

    private boolean accountContextAlreadyExists(String oid, SyncContext context) {
        for (AccountSyncContext accContext : context.getAccountContexts()) {
            if (oid.equals(accContext.getOid())) {
                return true;
            }
        }

        return false;
    }

    private void loadFromSystemConfig(SyncContext context, OperationResult result) throws ObjectNotFoundException,
            SchemaException {
        SystemConfigurationType systemConfigurationType = 
        	cacheRepositoryService.getObject(SystemConfigurationType.class, SystemObjectsType.SYSTEM_CONFIGURATION.value(), null,
        			result).asObjectable();
        if (systemConfigurationType == null) {
            // throw new SystemException("System configuration object is null (should not happen!)");
            // This should not happen, but it happens in tests. And it is a convenient short cut. Tolerate it for now.
            LOGGER.warn("System configuration object is null (should not happen!)");
            return;
        }

        if (context.getUserTemplate() == null) {
            UserTemplateType defaultUserTemplate = systemConfigurationType.getDefaultUserTemplate();
            context.setUserTemplate(defaultUserTemplate);
        }

        if (context.getAccountSynchronizationSettings() == null) {
            AccountSynchronizationSettingsType globalAccountSynchronizationSettings = systemConfigurationType.getGlobalAccountSynchronizationSettings();
            LOGGER.trace("Applying globalAccountSynchronizationSettings to context: {}", globalAccountSynchronizationSettings);
            context.setAccountSynchronizationSettings(globalAccountSynchronizationSettings);
        }
    }

}
