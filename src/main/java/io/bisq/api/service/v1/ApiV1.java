package io.bisq.api.service.v1;

import io.bisq.api.BisqProxy;
import io.swagger.annotations.*;

import javax.ws.rs.Path;


@SwaggerDefinition(
        securityDefinition = @SecurityDefinition(
                apiKeyAuthDefinitions = @ApiKeyAuthDefinition(
                        in = ApiKeyAuthDefinition.ApiKeyLocation.HEADER,
                        key = "accessToken",
                        name = "authorization"
                )
        )
)
@Api(authorizations = @Authorization(value = "accessToken"))
@Path("/api/v1")
public class ApiV1 {

    private final BisqProxy bisqProxy;

    public ApiV1(BisqProxy bisqProxy) {
        this.bisqProxy = bisqProxy;
    }

    @Path("arbitrators")
    public ArbitratorResource getArbitratorResource() {
        return new ArbitratorResource(bisqProxy);
    }

    @Path("closed-tradables")
    public ClosedTradableResource getClosedTradableResource() {
        return new ClosedTradableResource(bisqProxy);
    }

    @Path("currencies")
    public CurrencyResource getCurrencyResource() {
        return new CurrencyResource(bisqProxy);
    }

    @Path("markets")
    public MarketResource getMarketResource() {
        return new MarketResource(bisqProxy);
    }

    @Path("network")
    public NetworkResource getNetworkResource() {
        return new NetworkResource(bisqProxy);
    }

    @Path("offers")
    public OfferResource getOfferResource() {
        return new OfferResource(bisqProxy);
    }

    @Path("payment-accounts")
    public PaymentAccountResource getPaymentAccountResource() {
        return new PaymentAccountResource(bisqProxy);
    }

    @Path("preferences")
    public PreferencesResource getSettingsResource() {
        return new PreferencesResource(bisqProxy);
    }

    @Path("trades")
    public TradeResource getTradeResource() {
        return new TradeResource(bisqProxy);
    }

    @Path("user")
    public UserResource getUserResource() {
        return new UserResource(bisqProxy);
    }

    @Path("version")
    public VersionResource getVersionResource() {
        return new VersionResource(bisqProxy);
    }

    @Path("wallet")
    public WalletResource getWalletResource() {
        return new WalletResource(bisqProxy);
    }
}
