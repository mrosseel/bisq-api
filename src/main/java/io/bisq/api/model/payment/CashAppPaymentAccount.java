package io.bisq.api.model.payment;

import com.fasterxml.jackson.annotation.JsonTypeName;
import io.bisq.api.model.PaymentAccount;
import io.bisq.core.payment.payload.PaymentMethod;
import org.hibernate.validator.constraints.NotBlank;

@JsonTypeName(PaymentMethod.CASH_APP_ID)
public class CashAppPaymentAccount extends PaymentAccount {

    @NotBlank
    public String cashTag;

    public CashAppPaymentAccount() {
        super(PaymentMethod.CASH_APP_ID);
    }
}
