package net.corda.examples.obligation

import net.corda.core.contracts.*
import net.corda.core.transactions.LedgerTransaction
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.utils.sumCash
import java.security.PublicKey

class ObligationContract : Contract {

    companion object {
        @JvmStatic
        val INSURECAR_CONTRACT_ID = "net.corda.examples.obligation.ObligationContract"
    }

    interface Commands : CommandData {
        class Issue : TypeOnlyCommandData(), Commands
        class Transfer : TypeOnlyCommandData(), Commands
        class Settle : TypeOnlyCommandData(), Commands
    }

    override fun verify(tx: LedgerTransaction): Unit {
        val command = tx.commands.requireSingleCommand<Commands>()
        val setOfSigners = command.signers.toSet()
        when (command.value) {
            is Commands.Issue -> verifyIssue(tx, setOfSigners)
//            is Commands.Transfer -> verifyTransfer(tx, setOfSigners)
            is Commands.Settle -> verifySettle(tx, setOfSigners)
            else -> throw IllegalArgumentException("Unrecognised command.")
        }
    }

    private fun keysFromParticipants(policy: Policy): Set<PublicKey> {
        return policy.participants.map {
            it.owningKey
        }.toSet()
    }

    // This only allows one obligation issuance per transaction.
    private fun verifyIssue(tx: LedgerTransaction, signers: Set<PublicKey>) = requireThat {
        val policy = tx.outputsOfType<Policy>().single()
        "A newly issued policy must have a positive premium." using (policy.premium.quantity > 0)
        "The client and underwriter cannot be the same identity." using (policy.client != policy.underwriter)
        "Both client and underwriter together only may sign policy issue transaction." using
                (signers == keysFromParticipants(policy))
    }

    // This only allows one obligation transfer per transaction.
//    private fun verifyTransfer(tx: LedgerTransaction, signers: Set<PublicKey>) = requireThat {
//        "An obligation transfer transaction should only consume one input state." using (tx.inputs.size == 1)
//        "An obligation transfer transaction should only create one output state." using (tx.outputs.size == 1)
//        val input = tx.inputsOfType<Obligation>().single()
//        val output = tx.outputsOfType<Obligation>().single()
//        "Only the lender property may change." using (input.withoutLender() == output.withoutLender())
//        "The lender property must change in a transfer." using (input.lender != output.lender)
//        "The borrower, old lender and new lender only must sign an obligation transfer transaction" using
//                (signers == (keysFromParticipants(input) `union` keysFromParticipants(output)))
//    }

    private fun verifySettle(tx: LedgerTransaction, signers: Set<PublicKey>) = requireThat {
        // Check for the presence of an input policy state.
        val policyInputs = tx.inputsOfType<Policy>()
        "There must be one input policy." using (policyInputs.size == 1)

        // Check there are output cash states.
        // We don't care about cash inputs, the Cash contract handles those.
        val cash = tx.outputsOfType<Cash.State>()
        "There must be output cash." using (cash.isNotEmpty())

        // Check that the cash is being assigned to the client.
        val inputPolicy = policyInputs.single()
        val acceptableCash = cash.filter { it.owner == inputPolicy.client }
        "There must be output cash paid to the recipient." using (acceptableCash.isNotEmpty())

        val policyOutputs = tx.outputsOfType<Policy>()

        // If the policy has been partially settled then it should still exist.
        "There must be one output policy." using (policyOutputs.size == 1)

        // Check only the claim property changes.
        val outputPolicy = policyOutputs.single()
        "The premium may not change when settling." using (inputPolicy.premium == outputPolicy.premium)
        "The client may not change when settling." using (inputPolicy.client == outputPolicy.client)
        "The underwriter may not change when settling." using (inputPolicy.underwriter == outputPolicy.underwriter)
        "The linearId may not change when settling." using (inputPolicy.linearId == outputPolicy.linearId)

        // Checks the required parties have signed.
        "Both client and underwriter together only must sign policy settle transaction." using
                (signers == keysFromParticipants(inputPolicy))
    }
}