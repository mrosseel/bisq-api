package network.bisq.api.service.v1;

import java.util.Collection;
import java.util.stream.Collectors;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.Authorization;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import network.bisq.api.BisqProxy;
import network.bisq.api.model.Arbitrator;
import network.bisq.api.model.ArbitratorList;
import network.bisq.api.model.ArbitratorRegistration;
import org.hibernate.validator.constraints.NotBlank;

@Api(value = "arbitrators", authorizations = @Authorization(value = "accessToken"))
@Produces(MediaType.APPLICATION_JSON)
public class ArbitratorResource {

    private final BisqProxy bisqProxy;

    public ArbitratorResource(BisqProxy bisqProxy) {
        this.bisqProxy = bisqProxy;
    }

    @ApiOperation("Unregister yourself as arbitrator")
    @DELETE
    public void unregister() {
        throw new WebApplicationException(Response.Status.NOT_IMPLEMENTED);
    }

    @ApiOperation("Register yourself as arbitrator")
    @POST
    public void register(@Valid @NotNull ArbitratorRegistration data) {
        bisqProxy.registerArbitrator(data.languageCodes);
    }

    @ApiOperation(value = "Find available arbitrators")
    @GET
    public ArbitratorList find(@QueryParam("acceptedOnly") boolean acceptedOnly) {
        return toRestModel(bisqProxy.getArbitrators(acceptedOnly));
    }

    @ApiOperation("Select arbitrator")
    @POST
    @Path("/{address}/select")
    public ArbitratorList selectArbitrator(@NotBlank @PathParam("address") String address) {
        return toRestModel(bisqProxy.selectArbitrator(address));
    }

    @ApiOperation("Deselect arbitrator")
    @POST
    @Path("/{address}/deselect")
    public ArbitratorList deselectArbitrator(@NotBlank @PathParam("address") String address) {
        return toRestModel(bisqProxy.deselectArbitrator(address));
    }

    private static ArbitratorList toRestModel(Collection<bisq.core.arbitration.Arbitrator> businessModelList) {
        final ArbitratorList arbitratorList = new ArbitratorList();
        arbitratorList.arbitrators = businessModelList
                .stream()
                .map(arbitrator -> new Arbitrator(arbitrator.getNodeAddress().getFullAddress()))
                .collect(Collectors.toList());
        arbitratorList.total = arbitratorList.arbitrators.size();
        return arbitratorList;
    }
}
