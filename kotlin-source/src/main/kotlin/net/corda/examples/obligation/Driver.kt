package net.corda.examples.obligation

import net.corda.core.contracts.Amount
import net.corda.core.contracts.LinearState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.utilities.toBase58String
import java.util.*

data class Driver(val wallet: Amount<Currency>,
                  val InsuranceCompany: AbstractParty,
                  val driver: AbstractParty,
                  val paid: Amount<Currency>,
                  val coveragePercentage: Int,
                  val policy: Policy,
                  override val linearId: UniqueIdentifier = UniqueIdentifier()) : LinearState {

    override val participants: List<AbstractParty> get() = listOf(InsuranceCompany, driver)

    fun pay(amountToPay: Amount<Currency>) = copy(paid = paid + amountToPay)

    fun payDeductible(deductible: Amount<Currency>) = copy(wallet = wallet - deductible)

    override fun toString(): String {
        val insurerString = (InsuranceCompany as? Party)?.name?.organisation ?: InsuranceCompany.owningKey.toBase58String()
        val driverString = (driver as? Party)?.name?.organisation ?: driver.owningKey.toBase58String()
        return ""
    }
}