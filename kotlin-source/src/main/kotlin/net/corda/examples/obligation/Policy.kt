//Package location
package net.corda.examples.obligation

//Necessary imports
import net.corda.core.contracts.Amount
import net.corda.core.contracts.LinearState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.Party
import net.corda.core.utilities.toBase58String
import net.corda.core.crypto.NullKeys
import java.util.*

//Policy Class, includes premium, claim, client, underwriter, flight, and policyID
data class Policy(val user: String,
                  val premium: Amount<Currency>,
                  val claim: Amount<Currency>,
                  val client: Party,
                  val underwriter: Party,
                  val date: String,
                  override val linearId: UniqueIdentifier = UniqueIdentifier()): LinearState{

    //Get clients and underwriters
    override val participants: List<Party> get() = listOf(client, underwriter)

    //Functions to update policy parameters
    fun payPremium(amountToPay: Amount<Currency>) = copy(premium = premium + amountToPay)
    fun payClaim(amountToPay: Amount<Currency>) = copy(claim = claim + amountToPay)
    fun updateDate(newDate: String) = copy(date = newDate)
    fun withNewClient(newClient: Party) = copy(client = newClient)
    fun withNewUnderwriter(newUnderwriter: Party) = copy(underwriter = newUnderwriter)

    //Provides response
    override fun toString(): String {
        val clientString = (client as? Party)?.name?.organisation ?: client.owningKey.toBase58String()
        val underwriterString = (underwriter as? Party)?.name?.organisation ?: underwriter.owningKey.toBase58String()
        return ""
    }
}