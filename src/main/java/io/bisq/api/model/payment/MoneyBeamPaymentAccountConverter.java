package io.bisq.api.model.payment;

import io.bisq.core.payment.MoneyBeamAccount;

public class MoneyBeamPaymentAccountConverter extends AbstractPaymentAccountConverter<MoneyBeamAccount, MoneyBeamPaymentAccount> {

    @Override
    public MoneyBeamAccount toBusinessModel(MoneyBeamPaymentAccount rest) {
        final MoneyBeamAccount business = new MoneyBeamAccount();
        business.init();
        business.setAccountId(rest.accountId);
        toBusinessModel(business, rest);
        return business;
    }

    @Override
    public MoneyBeamPaymentAccount toRestModel(MoneyBeamAccount business) {
        final MoneyBeamPaymentAccount rest = new MoneyBeamPaymentAccount();
        rest.accountId = business.getAccountId();
        toRestModel(rest, business);
        return rest;
    }

}