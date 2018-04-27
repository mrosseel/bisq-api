package io.bisq.api.service.v1;

import bisq.core.btc.AddressEntryException;
import bisq.core.btc.InsufficientFundsException;
import io.bisq.api.BisqProxy;
import io.bisq.api.exceptions.AmountTooLowException;
import io.bisq.api.model.*;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.Authorization;
import org.bitcoinj.core.Coin;

import javax.validation.Valid;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.util.HashSet;


@Api(value = "wallet", authorizations = @Authorization(value = "accessToken"))
@Produces(MediaType.APPLICATION_JSON)
public class WalletResource {

    private final BisqProxy bisqProxy;

    WalletResource(BisqProxy bisqProxy) {
        this.bisqProxy = bisqProxy;
    }

    @ApiOperation(value = "Get wallet details")
    @GET
    public WalletDetails getWalletDetails() {
        return bisqProxy.getWalletDetails();
    }

    @ApiOperation("Get wallet addresses")
    @GET
    @Path("/addresses")
    public WalletAddressList getAddresses(@QueryParam("purpose") BisqProxy.WalletAddressPurpose purpose) {
        return bisqProxy.getWalletAddresses(purpose);
    }

    @ApiOperation("Get or create wallet address")
    @POST
    @Path("/addresses")
    public WalletAddress getOrCreateAvailableUnusedWalletAddresses() {
        return bisqProxy.getOrCreateAvailableUnusedWalletAddresses();
    }

    @ApiOperation("Get wallet transactions")
    @GET
    @Path("/transactions")
    public WalletTransactionList getTransactions() {
        return bisqProxy.getWalletTransactions();
    }

    @ApiOperation("Withdraw funds")
    @POST
    @Path("/withdraw")
    public void withdrawFunds(@Valid WithdrawFundsForm data) {
        final HashSet<String> sourceAddresses = new HashSet<>(data.sourceAddresses);
        final Coin amountAsCoin = Coin.valueOf(data.amount);
        final boolean feeExcluded = data.feeExcluded;
        final String targetAddress = data.targetAddress;
        try {
            bisqProxy.withdrawFunds(sourceAddresses, amountAsCoin, feeExcluded, targetAddress);
        } catch (AddressEntryException e) {
            throw new WebApplicationException(e.getMessage(), 423);
        } catch (InsufficientFundsException e) {
            throw new WebApplicationException(e.getMessage(), 423);
        } catch (AmountTooLowException e) {
            throw new WebApplicationException(e.getMessage(), 424);
        }
    }
}
