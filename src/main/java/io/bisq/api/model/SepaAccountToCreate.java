package io.bisq.api.model;


import com.fasterxml.jackson.annotation.JsonTypeName;
import io.bisq.api.model.validation.CountryCode;
import io.bisq.core.payment.payload.PaymentMethod;
import org.hibernate.validator.constraints.NotBlank;

@JsonTypeName(PaymentMethod.SEPA_ID)
public class SepaAccountToCreate extends AccountToCreate {

    @NotBlank
    public String accountName;

    @CountryCode
    @NotBlank
    public String countryCode;

    @NotBlank
    public String holderName;

    @NotBlank
    public String bic;

    @NotBlank
    public String iban;

}