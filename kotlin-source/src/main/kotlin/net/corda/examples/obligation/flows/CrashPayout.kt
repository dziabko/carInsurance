package net.corda.examples.obligation.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Amount
import net.corda.core.contracts.Command
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.seconds
import net.corda.examples.obligation.ObligationContract
import net.corda.examples.obligation.Policy
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.contracts.getCashBalance
import java.time.LocalDateTime
import java.util.*

object CrashPayout {
    @InitiatingFlow
    @StartableByRPC
    class Initiator(private val linearId: UniqueIdentifier,
                    private val claim: Int,
                    private val status: String) : InsureCarBaseFlow() {

        companion object {
            object PREPARATION : ProgressTracker.Step("Obtaining policy from vault.")
            object BUILDING : ProgressTracker.Step("Building and verifying transaction.")
            object SIGNING : ProgressTracker.Step("Signing transaction.")
            object COLLECTING : ProgressTracker.Step("Collecting counterparty signature.") {
                override fun childProgressTracker() = CollectSignaturesFlow.tracker()
            }
            object FINALIZING : ProgressTracker.Step("Finalizing transaction.") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            fun tracker() = ProgressTracker(PREPARATION, BUILDING, SIGNING, COLLECTING, FINALIZING)
        }

        override val progressTracker: ProgressTracker = tracker()

        @Suspendable
        override fun call(): SignedTransaction {
            // Stage 1. Retrieve policy specified by linearId from the vault.
            progressTracker.currentStep = PREPARATION
            val policyToSettle = getPolicyByLinearId(linearId)
            val inputPolicy = policyToSettle.state.data

            // Stage 2. Check we have enough cash to settle the requested amount and add claim amount.
            check(status.equals("C")) {
                throw FlowException("A crash must have occurred for a payout.")
            }

            val cashBalance = serviceHub.getCashBalance(inputPolicy.claim.token)
            check(cashBalance.quantity > 0) {
                throw FlowException("Underwriter has no ${inputPolicy.claim.token} to settle.")
            }
            check(inputPolicy.claim.quantity == 0.toLong()){
                throw FlowException("Claim of ${inputPolicy.claim} has been settled.")
            }

//            var claim = (Amount(0.toLong(), Currency.getInstance("CAD")))
//            if ((minutes >= 15) && (minutes < 30)) {
//                claim = (Amount(inputPolicy.premium.quantity.toLong() / 2.toLong(), Currency.getInstance("CAD")))
//            }
//            else if ((minutes >= 30) && (minutes < 45)) {
//                claim = (Amount(inputPolicy.premium.quantity.toLong(), Currency.getInstance("CAD")))
//            }
//            else if ((minutes >= 45) || (status.equals("C"))) {
//                claim = (Amount(inputPolicy.premium.quantity.toLong() * 2, Currency.getInstance("CAD")))
//            }
//            else {
//                claim = (Amount(0.toLong(), Currency.getInstance("CAD")))
//            }

            // Stage 3. Create a settle command.
            val settleCommand = Command(
                    ObligationContract.Commands.Settle(),
                    inputPolicy.participants.map { it.owningKey })

            // Stage 4. Create a transaction builder. Add the settle command and input policy.
            progressTracker.currentStep = BUILDING
            val date = LocalDateTime.now().toString()
            //TODO: Change the currency's type
            val amountClaim = Amount(claim.toLong(), Currency.getInstance("CAD"))
            val outputPolicy = inputPolicy.payClaim(amountClaim).updateDate(date)
//            inputPolicy.pa
//            val outputpolicy2 = outputPolicy1.updateDate(date)
//            val outputPolicy = outputpolicy2.updateStatus("Settled")
            val builder = TransactionBuilder(firstNotary)
                    .addInputState(policyToSettle)
                    .addOutputState(outputPolicy, ObligationContract.INSURECAR_CONTRACT_ID)
                    .addCommand(settleCommand)

            // Stage 5. Get some cash from the vault and add a spend to our transaction builder.
            // We pay cash to the client's policy key.
            val (_, cashSigningKeys) = Cash.generateSpend(serviceHub, builder, amountClaim, inputPolicy.client)
            check(cashSigningKeys == cashSigningKeys){
                throw FlowException("")
            }

            // Stage 6. Verify and sign the transaction.
            progressTracker.currentStep = SIGNING
            builder.verify(serviceHub)
            val ptx = serviceHub.signInitialTransaction(builder, cashSigningKeys + inputPolicy.underwriter.owningKey)

            // Stage 7. Get counterparty signature.
            progressTracker.currentStep = COLLECTING
            val session = initiateFlow(inputPolicy.client)
            val stx = subFlow(CollectSignaturesFlow(
                    ptx,
                    listOf(session),
                    COLLECTING.childProgressTracker())
            )

            // Stage 8. Finalize the transaction.
            progressTracker.currentStep = FINALIZING
            return subFlow(FinalityFlow(stx, FINALIZING.childProgressTracker()))
        }
    }

    // Allows counterparty to respond.
    @InitiatedBy(Initiator::class)
    class CrashPayoutResponder(val otherPartySession: FlowSession) : FlowLogic<Unit>() {
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