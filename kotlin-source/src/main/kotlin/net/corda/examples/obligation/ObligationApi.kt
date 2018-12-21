package net.corda.examples.obligation

import net.corda.core.contracts.Amount
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.CordaX500Name
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.utilities.OpaqueBytes
import net.corda.core.utilities.getOrThrow
import net.corda.examples.obligation.flows.*
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.contracts.getCashBalances
import net.corda.finance.flows.CashIssueFlow
import java.util.*
import javax.ws.rs.GET
import javax.ws.rs.Path
import javax.ws.rs.Produces
import javax.ws.rs.QueryParam
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response
import javax.ws.rs.core.Response.Status.BAD_REQUEST
import javax.ws.rs.core.Response.Status.CREATED

@Path("obligation")
class ObligationApi(val rpcOps: CordaRPCOps) {

    private val myIdentity = rpcOps.nodeInfo().legalIdentities.first()

    @GET
    @Path("me")
    @Produces(MediaType.APPLICATION_JSON)
    fun me() = mapOf("me" to myIdentity)

    @GET
    @Path("peers")
    @Produces(MediaType.APPLICATION_JSON)
    fun peers() = mapOf("peers" to rpcOps.networkMapSnapshot()
            .filter { nodeInfo -> nodeInfo.legalIdentities.first() != myIdentity }
            .map { it.legalIdentities.first().name.organisation })

    @GET
    @Path("owed-per-currency")
    @Produces(MediaType.APPLICATION_JSON)
    fun owedPerCurrency() = rpcOps.vaultQuery(Obligation::class.java).states
            .filter { (state) -> state.data.lender != myIdentity }
            .map { (state) -> state.data.amount }
            .groupBy({ amount -> amount.token }, { (quantity) -> quantity })
            .mapValues { it.value.sum() }

    @GET
    @Path("obligations")
    @Produces(MediaType.APPLICATION_JSON)
    fun obligations() = rpcOps.vaultQuery(Obligation::class.java).states

    @GET
    @Path("cash")
    @Produces(MediaType.APPLICATION_JSON)
    fun cash() = rpcOps.vaultQuery(Cash.State::class.java).states

    @GET
    @Path("cash-balances")
    @Produces(MediaType.APPLICATION_JSON)
    fun getCashBalances() = rpcOps.getCashBalances()

    @GET
    @Path("self-issue-cash")
    fun selfIssueCash(@QueryParam(value = "amount") amount: Int,
                      @QueryParam(value = "currency") currency: String): Response {

        // 1. Prepare issue request.
        val issueAmount = Amount(amount.toLong() * 100, Currency.getInstance(currency))
        val notary = rpcOps.notaryIdentities().firstOrNull() ?: throw IllegalStateException("Could not find a notary.")
        val issueRef = OpaqueBytes.of(0)
        val issueRequest = CashIssueFlow.IssueRequest(issueAmount, issueRef, notary)

        // 2. Start flow and wait for response.
        val (status, message) = try {
            val flowHandle = rpcOps.startFlowDynamic(CashIssueFlow::class.java, issueRequest)
            val result = flowHandle.use { it.returnValue.getOrThrow() }
            CREATED to result.stx.tx.outputs.single().data
        } catch (e: Exception) {
            BAD_REQUEST to e.message
        }

        // 3. Return the response.
        return Response.status(status).entity(message).build()
    }

    @GET
    @Path("issue-obligation")
    fun issuePolicy(@QueryParam(value = "amount") amount: Int,
                    @QueryParam(value = "currency") currency: String,
                    @QueryParam(value = "party") party: String): Response {
        // 1. Get party objects for the counterparty.
        val lenderIdentity = rpcOps.partiesFromName(party, exactMatch = false).singleOrNull()
                ?: throw IllegalStateException("Couldn't lookup node identity for $party.")

        // 2. Create an amount object.
        val issueAmount = Amount(amount.toLong() * 100, Currency.getInstance(currency))

        // 3. Start the IssueObligation flow. We block and wait for the flow to return.
        val (status, message) = try {
            val flowHandle = rpcOps.startFlowDynamic(
                    IssueObligation.Initiator::class.java,
                    issueAmount,
                    lenderIdentity,
                    true
            )

            val result = flowHandle.use { it.returnValue.getOrThrow() }
            CREATED to "Transaction id ${result.id} committed to ledger.\n${result.tx.outputs.single().data}"
        } catch (e: Exception) {
            BAD_REQUEST to e.message
        }

        // 4. Return the result.
        return Response.status(status).entity(message).build()
    }

    @GET
    @Path("transfer-obligation")
    fun transferObligation(@QueryParam(value = "id") id: String,
                           @QueryParam(value = "party") party: String): Response {
        val linearId = UniqueIdentifier.fromString(id)
        val newLender = rpcOps.partiesFromName(party, exactMatch = false).singleOrNull()
                ?: throw IllegalStateException("Couldn't lookup node identity for $party.")

        val (status, message) = try {
            val flowHandle = rpcOps.startFlowDynamic(
                    TransferObligation.Initiator::class.java,
                    linearId,
                    newLender,
                    true
            )

            flowHandle.use { flowHandle.returnValue.getOrThrow() }
            CREATED to "Obligation $id transferred to $party."
        } catch (e: Exception) {
            BAD_REQUEST to e.message
        }

        return Response.status(status).entity(message).build()
    }

    //Issue a policy to a user
    @GET
    @Path("issue-policy")
    fun issuePolicy(@QueryParam(value = "user") user: String,
                    @QueryParam(value = "premium") premium: Int,
                    @QueryParam(value = "flight") flight: String,
                    @QueryParam(value = "fStatus") fStatus: String): Response {

        // 1. Create a premium object.
        val issuePremium = Amount(premium.toLong() * 100, Currency.getInstance("CAD"))
        val client = rpcOps.wellKnownPartyFromX500Name(CordaX500Name.parse("O=Client,L=Winnipeg,C=CA"))
        val underwriter = rpcOps.wellKnownPartyFromX500Name(CordaX500Name.parse("O=Wawanesa,L=Winnipeg,C=CA"))

        // 2. Start the IssuePolicy flow. We block and wait for the flow to return.
        val (status, message) = try {
            val flowHandle = rpcOps.startFlowDynamic(
                    IssuePolicy.Initiator::class.java,
                    user,
                    issuePremium,
                    client,
                    underwriter
            )

            val result = flowHandle.use { it.returnValue.getOrThrow() }
            CREATED to "Transaction completed."
        } catch (e: Exception) {
            BAD_REQUEST to e.message
        }

        // 3. Return the result.
        return Response.status(status).entity(message).build()
    }


    //In the event of a crash, insurer pays the claim to the driver
    @GET
    @Path("payout-policy")
    fun settlePolicy(@QueryParam(value = "id") id: String,
                     @QueryParam(value = "claim") claim: Int,
                     @QueryParam(value = "status") fStatus: String): Response {
        // 1. Get party objects for the counterparty.
        val linearId = UniqueIdentifier.fromString(id)

        // 2. Start the SettlePolicy flow. We block and wait for the flow to return.
        val (status, message) = try {
            val flowHandle = rpcOps.startFlowDynamic(
                    CrashPayout.Initiator::class.java,
                    linearId,
                    claim,
                    fStatus
            )

            flowHandle.use { flowHandle.returnValue.getOrThrow() }
            CREATED to "Transaction completed."
        } catch (e: Exception) {
            BAD_REQUEST to e.message
        }

        // 3. Return the result.
        return Response.status(status).entity(message).build()
    }
}