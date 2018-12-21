package net.corda.examples.obligation.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.confidential.SwapIdentitiesFlow
import net.corda.core.contracts.Amount
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.ProgressTracker.Step
import net.corda.core.utilities.seconds
import net.corda.examples.obligation.ObligationContract
import net.corda.examples.obligation.ObligationContract.Companion.INSURECAR_CONTRACT_ID
import net.corda.examples.obligation.Policy
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.contracts.getCashBalance
import java.time.LocalDateTime
import java.util.*

//NOTE: IssuePolicy must be executed on client interface only
object IssuePolicy {
    @InitiatingFlow
    @StartableByRPC
    class Initiator(private val user: String,
                    private val premium: Amount<Currency>,
                    private val client: Party,
                    private val underwriter: Party) : InsureCarBaseFlow() {

        companion object {
            object INITIALIZING : Step("Performing initial steps.")
            object BUILDING : Step("Building and verifying transaction.")
            object SIGNING : Step("Signing transaction.")
            object COLLECTING : Step("Collecting counterparty signature.") {
                override fun childProgressTracker() = CollectSignaturesFlow.tracker()
            }
            object FINALIZING : Step("Finalizing transaction.") {
                override fun childProgressTracker() = FinalityFlow.tracker()
        }

            fun tracker() = ProgressTracker(INITIALIZING, BUILDING, SIGNING, COLLECTING, FINALIZING)
        }

        override val progressTracker: ProgressTracker = tracker()

        @Suspendable
        override fun call(): SignedTransaction {
            // Step 1. Initialization.
            progressTracker.currentStep = INITIALIZING
            val claim =  Amount(0.toLong() * 100, Currency.getInstance("CAD"))
            val pStatus = "Pending"
            val date = LocalDateTime.now().toString()
            val policy = Policy(user, premium, claim, client, underwriter, date)
            val ourSigningKey = policy.client.owningKey

            // Stage 2. Check client has enough cash to issue a policy and that the flight is grounded.
            val cashBalance = serviceHub.getCashBalance(premium.token)
            check(cashBalance.quantity > 0) {
                throw FlowException("Client has no ${premium.token} to receive a policy.")
            }
            check(cashBalance >= premium) {
                throw FlowException("Client has only $cashBalance but needs $premium to receive a policy.")
            }

            // Step 3. Building.
            progressTracker.currentStep = BUILDING
            val notary = serviceHub.networkMapCache.notaryIdentities[0]
            val utx = TransactionBuilder(notary = notary)
                    .addOutputState(policy, INSURECAR_CONTRACT_ID)
                    .addCommand(ObligationContract.Commands.Issue(), policy.participants.map { it.owningKey })
                    .setTimeWindow(serviceHub.clock.instant(), 30.seconds)

            // Stage 4. Get some cash from the vault and add a spend to our transaction builder.
            // We pay cash to the underwriter's policy key.
            val (_, cashSigningKeys) = Cash.generateSpend(serviceHub, utx, premium, underwriter)

            // Step 5. Sign the transaction.
            progressTracker.currentStep = SIGNING
            val ptx = serviceHub.signInitialTransaction(utx, cashSigningKeys + ourSigningKey)

            // Step 6. Get the counter-party signature.
            progressTracker.currentStep = COLLECTING
            val otherpartySession = initiateFlow(policy.underwriter)
            val stx = subFlow(CollectSignaturesFlow(
                    ptx,
                    listOf(otherpartySession),
                    COLLECTING.childProgressTracker())
            )


            // Step 7. Finalize the transaction.
            progressTracker.currentStep = FINALIZING
            return subFlow(FinalityFlow(stx, FINALIZING.childProgressTracker()))
        }
    }

    // Allows counterparty to respond.
    @InitiatedBy(Initiator::class)
    class IssuePolicyResponder(val otherPartySession: FlowSession) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            val signTransactionFlow = object : SignTransactionFlow(otherPartySession, SignTransactionFlow.tracker()) {
                override fun checkTransaction(stx: SignedTransaction) = requireThat {
                }
            }

            subFlow(signTransactionFlow)
        }
    }
}