package io.bisq.api.model.payment;

import com.fasterxml.jackson.annotation.JsonTypeName;
import io.bisq.api.model.PaymentAccount;
import io.bisq.core.payment.payload.PaymentMethod;
import org.hibernate.validator.constraints.NotBlank;

@JsonTypeName(PaymentMethod.REVOLUT_ID)
public class RevolutPaymentAccount extends PaymentAccount {

    @NotBlank
    public String accountId;

    public RevolutPaymentAccount() {
        super(PaymentMethod.REVOLUT_ID);
    }
}