package com.insureflight.flows

import com.google.common.collect.ImmutableList
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.FlowException
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.flows.SignTransactionFlow
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.node.services.Vault
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import com.insureflight.Policy

/**
 * An abstract FlowLogic class that is subclassed by the policy flows to
 * provide helper methods and classes.
 */
abstract class InsureFlightBaseFlow : FlowLogic<SignedTransaction>() {

    val firstNotary get() = serviceHub.networkMapCache.notaryIdentities.firstOrNull()
            ?: throw FlowException("No available notary.")

    fun getPolicyByLinearId(linearId: UniqueIdentifier): StateAndRef<Policy> {
        val queryCriteria = QueryCriteria.LinearStateQueryCriteria(
                null,
                ImmutableList.of(linearId),
                Vault.StateStatus.UNCONSUMED, null)

        return serviceHub.vaultService.queryBy<Policy>(queryCriteria).states.singleOrNull()
                ?: throw FlowException("Policy with id $linearId not found.")
    }

    fun resolveIdentity(abstractParty: AbstractParty): Party {
        return serviceHub.identityService.requireWellKnownPartyFromAnonymous(abstractParty)
    }
}
