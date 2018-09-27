package net.corda.examples.obligation


data class Driver(val wallet: Amount<Currency>,
                  val InsuranceCompany: AbstractParty,
                  val driver: AbstractParty,
                  val coveragePercentage: Int,
                  override val linearId: UniqueIdentifier = UniqueIdentifier()) : LinearState {

    override val participants: List<AbstractParty> get() = listOf(InsuranceCompany, driver)

    fun pay(amountToPay: Amount<Currency>) = copy(paid = paid + amountToPay)

    fun payDeductible(deductible: Amount<Currency>) = copy(wallet = wallet - deductible)

    override fun toString(): String {
        val insurerString = (InsuranceCompany as? Party)?.name?.organisation ?: InsuranceCompany.owningKey.toBase58String()
        val driverString = (driver as? Party)?.name?.organisation ?: driver.owningKey.toBase58String()
        return "Obligation($linearId): $driverString owes $insurerString $amount and has paid $paid so far."
    }
}