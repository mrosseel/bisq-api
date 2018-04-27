package io.bisq.api;

import bisq.common.app.DevEnv;
import bisq.common.app.Version;
import bisq.common.crypto.KeyRing;
import bisq.common.handlers.ErrorMessageHandler;
import bisq.common.handlers.ResultHandler;
import bisq.common.util.Tuple2;
import bisq.core.app.BisqEnvironment;
import bisq.core.arbitration.Arbitrator;
import bisq.core.arbitration.ArbitratorManager;
import bisq.core.btc.*;
import bisq.core.btc.wallet.*;
import bisq.core.locale.*;
import bisq.core.offer.*;
import bisq.core.payment.AccountAgeWitnessService;
import bisq.core.payment.CryptoCurrencyAccount;
import bisq.core.payment.PaymentAccount;
import bisq.core.payment.validation.AltCoinAddressValidator;
import bisq.core.provider.fee.FeeService;
import bisq.core.provider.price.MarketPrice;
import bisq.core.provider.price.PriceFeedService;
import bisq.core.trade.BuyerAsMakerTrade;
import bisq.core.trade.SellerAsMakerTrade;
import bisq.core.trade.Trade;
import bisq.core.trade.TradeManager;
import bisq.core.trade.closed.ClosedTradableManager;
import bisq.core.trade.failed.FailedTradesManager;
import bisq.core.trade.protocol.*;
import bisq.core.user.BlockChainExplorer;
import bisq.core.user.User;
import bisq.core.util.CoinUtil;
import bisq.core.util.validation.InputValidator;
import bisq.desktop.util.validation.BtcAddressValidator;
import bisq.network.p2p.NodeAddress;
import bisq.network.p2p.P2PService;
import bisq.network.p2p.network.Statistic;
import com.google.common.util.concurrent.FutureCallback;
import com.google.inject.Injector;
import io.bisq.api.exceptions.*;
import io.bisq.api.exceptions.InsufficientMoneyException;
import io.bisq.api.model.*;
import io.bisq.api.model.payment.PaymentAccountHelper;
import io.bisq.api.service.TokenRegistry;
import javafx.collections.ObservableList;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.*;
import org.bitcoinj.crypto.KeyCrypterScrypt;
import org.bitcoinj.wallet.Wallet;
import org.jetbrains.annotations.NotNull;
import org.spongycastle.crypto.params.KeyParameter;

import javax.annotation.Nullable;
import javax.validation.ValidationException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static bisq.core.payment.PaymentAccountUtil.isPaymentAccountValidForOffer;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.stream.Collectors.toList;

/**
 * This class is a proxy for all bitsquare features the model will use.
 * <p>
 * No methods/representations used in the interface layers (REST/Socket/...) should be used in this class.
 * => this should be the common gateway to bisq used by all outward-facing API classes.
 * <p>
 * If the bisq code is refactored correctly, this class could become very light.
 */
@Slf4j
public class BisqProxy {
    private final Injector injector;
    private AccountAgeWitnessService accountAgeWitnessService;
    private ArbitratorManager arbitratorManager;
    private BtcWalletService btcWalletService;
    private User user;
    private TradeManager tradeManager;
    private ClosedTradableManager closedTradableManager;
    private FailedTradesManager failedTradesManager;
    private OpenOfferManager openOfferManager;
    private OfferBookService offerBookService;
    private P2PService p2PService;
    private KeyRing keyRing;
    private FeeService feeService;
    private bisq.core.user.Preferences preferences;
    private BsqWalletService bsqWalletService;
    private final boolean useDevPrivilegeKeys;
    private WalletsSetup walletsSetup;
    @Getter
    private MarketList marketList;
    @Getter
    private CurrencyList currencyList;

    public BisqProxy(Injector injector, AccountAgeWitnessService accountAgeWitnessService, ArbitratorManager arbitratorManager, BtcWalletService btcWalletService, TradeManager tradeManager, OpenOfferManager openOfferManager,
                     OfferBookService offerBookService, P2PService p2PService, KeyRing keyRing, User user,
                     FeeService feeService, bisq.core.user.Preferences preferences, BsqWalletService bsqWalletService, WalletsSetup walletsSetup,
                     ClosedTradableManager closedTradableManager, FailedTradesManager failedTradesManager, boolean useDevPrivilegeKeys) {
        this.injector = injector;
        this.accountAgeWitnessService = accountAgeWitnessService;
        this.arbitratorManager = arbitratorManager;
        this.btcWalletService = btcWalletService;
        this.tradeManager = tradeManager;
        this.openOfferManager = openOfferManager;
        this.offerBookService = offerBookService;
        this.p2PService = p2PService;
        this.keyRing = keyRing;
        this.user = user;
        this.feeService = feeService;
        this.preferences = preferences;
        this.bsqWalletService = bsqWalletService;
        this.marketList = calculateMarketList();
        this.currencyList = calculateCurrencyList();
        this.walletsSetup = walletsSetup;
        this.closedTradableManager = closedTradableManager;
        this.failedTradesManager = failedTradesManager;
        this.useDevPrivilegeKeys = useDevPrivilegeKeys;
    }

    public static CurrencyList calculateCurrencyList() {
        CurrencyList currencyList = new CurrencyList();
        CurrencyUtil.getAllSortedCryptoCurrencies().forEach(cryptoCurrency -> currencyList.add(cryptoCurrency.getCode(), cryptoCurrency.getName(), "crypto"));
        CurrencyUtil.getAllSortedFiatCurrencies().forEach(fiatCurrency -> currencyList.add(fiatCurrency.getCurrency().getCurrencyCode(), fiatCurrency.getName(), "fiat"));
        Collections.sort(currencyList.currencies, (io.bisq.api.model.Currency p1, io.bisq.api.model.Currency p2) -> p1.name.compareTo(p2.name));
        return currencyList;
    }

    public static MarketList calculateMarketList() {
        MarketList marketList = new MarketList();
        CurrencyList currencyList = calculateCurrencyList(); // we calculate this twice but only at startup
        //currencyList.getCurrencies().stream().flatMap(currency -> marketList.getMarkets().forEach(currency1 -> cur))
        List<Market> btc = CurrencyUtil.getAllSortedCryptoCurrencies().stream().filter(cryptoCurrency -> !(cryptoCurrency.getCode().equals("BTC"))).map(cryptoCurrency -> new Market(cryptoCurrency.getCode(), "BTC")).collect(toList());
        marketList.markets.addAll(btc);
        btc = CurrencyUtil.getAllSortedFiatCurrencies().stream().map(cryptoCurrency -> new Market("BTC", cryptoCurrency.getCode())).collect(toList());
        marketList.markets.addAll(btc);
        Collections.sort(currencyList.currencies, Comparator.comparing(p -> p.name));
        return marketList;
    }

    public PaymentAccount addPaymentAccount(PaymentAccount paymentAccount) {
        if (paymentAccount instanceof CryptoCurrencyAccount) {
            final CryptoCurrencyAccount cryptoCurrencyAccount = (CryptoCurrencyAccount) paymentAccount;
            final TradeCurrency tradeCurrency = cryptoCurrencyAccount.getSingleTradeCurrency();
            if (null == tradeCurrency) {
                throw new ValidationException("There must be exactly one trade currency");
            }
            final AltCoinAddressValidator altCoinAddressValidator = injector.getInstance(AltCoinAddressValidator.class);
            altCoinAddressValidator.setCurrencyCode(tradeCurrency.getCode());
            final InputValidator.ValidationResult validationResult = altCoinAddressValidator.validate(cryptoCurrencyAccount.getAddress());
            if (!validationResult.isValid) {
                throw new ValidationException(validationResult.errorMessage);
            }
        }
        user.addPaymentAccount(paymentAccount);
        TradeCurrency singleTradeCurrency = paymentAccount.getSingleTradeCurrency();
        List<TradeCurrency> tradeCurrencies = paymentAccount.getTradeCurrencies();
        if (singleTradeCurrency != null) {
            if (singleTradeCurrency instanceof FiatCurrency)
                preferences.addFiatCurrency((FiatCurrency) singleTradeCurrency);
            else
                preferences.addCryptoCurrency((CryptoCurrency) singleTradeCurrency);
        } else if (tradeCurrencies != null && !tradeCurrencies.isEmpty()) {
            if (tradeCurrencies.contains(CurrencyUtil.getDefaultTradeCurrency()))
                paymentAccount.setSelectedTradeCurrency(CurrencyUtil.getDefaultTradeCurrency());
            else
                paymentAccount.setSelectedTradeCurrency(tradeCurrencies.get(0));

            tradeCurrencies.forEach(tradeCurrency -> {
                if (tradeCurrency instanceof FiatCurrency)
                    preferences.addFiatCurrency((FiatCurrency) tradeCurrency);
                else
                    preferences.addCryptoCurrency((CryptoCurrency) tradeCurrency);
            });
        }

        accountAgeWitnessService.publishMyAccountAgeWitness(paymentAccount.getPaymentAccountPayload());
        return paymentAccount;
    }


    public void removePaymentAccount(String id) {
        final PaymentAccount paymentAccount = user.getPaymentAccount(id);
        if (null == paymentAccount) {
            throw new NotFoundException("Payment account not found: " + id);
        }
        user.removePaymentAccount(paymentAccount);
    }

    private List<PaymentAccount> getPaymentAccountList() {
        return new ArrayList(user.getPaymentAccounts());
    }

    private PaymentAccount getPaymentAccount(String paymentAccountId) {
        return user.getPaymentAccount(paymentAccountId);
    }

    public PaymentAccountList getAccountList() {
        PaymentAccountList paymentAccountList = new PaymentAccountList();
        paymentAccountList.paymentAccounts = getPaymentAccountList().stream()
                .map(PaymentAccountHelper::toRestModel)
                .collect(Collectors.toList());
        return paymentAccountList;
    }

    public CompletableFuture<Void> offerCancel(String offerId) {
        final CompletableFuture<Void> futureResult = new CompletableFuture<>();
        Optional<OpenOffer> openOfferById = openOfferManager.getOpenOfferById(offerId);
        if (!openOfferById.isPresent()) {
            return failFuture(futureResult, new NotFoundException("Offer not found: " + offerId));
        }
        openOfferManager.removeOpenOffer(openOfferById.get(),
                () -> futureResult.complete(null),
                error -> futureResult.completeExceptionally(new RuntimeException(error)));
        return futureResult;
    }

    public Offer getOffer(String offerId) {
        final String safeOfferId = (null == offerId) ? "" : offerId;
        final Optional<Offer> offerOptional = offerBookService.getOffers().stream().filter(offer1 -> safeOfferId.equals(offer1.getId())).findAny();
        if (!offerOptional.isPresent()) {
            throw new NotFoundException("Offer not found: " + offerId);
        }
        return offerOptional.get();
    }

    public List<Offer> getOfferList() {
        return offerBookService.getOffers();
    }

    public CompletableFuture<Offer> offerMake(boolean fundUsingBisqWallet, String offerId, String accountId, OfferPayload.Direction direction, long amount, long minAmount,
                                              boolean useMarketBasedPrice, Double marketPriceMargin, String marketPair, long fiatPrice, Long buyerSecurityDeposit) {
        // exception from gui code is not clear enough, so this check is added. Missing money is another possible check but that's clear in the gui exception.
        final CompletableFuture<Offer> futureResult = new CompletableFuture<>();

        if (!fundUsingBisqWallet && null == offerId)
            return failFuture(futureResult, new ValidationException("Specify offerId of earlier prepared offer if you want to use dedicated wallet address."));

        final OfferBuilder offerBuilder = injector.getInstance(OfferBuilder.class);
        final Offer offer;
        try {
            offer = offerBuilder.build(offerId, accountId, direction, amount, minAmount, useMarketBasedPrice, marketPriceMargin, marketPair, fiatPrice, buyerSecurityDeposit);
        } catch (Exception e) {
            return failFuture(futureResult, e);
        }
        Coin reservedFundsForOffer = OfferUtil.isBuyOffer(direction) ? preferences.getBuyerSecurityDepositAsCoin() : Restrictions.getSellerSecurityDeposit();
        if (!OfferUtil.isBuyOffer(direction))
            reservedFundsForOffer = reservedFundsForOffer.add(Coin.valueOf(amount));

//        TODO check if there is sufficient money cause openOfferManager will log exception and pass just message
//        TODO openOfferManager should return CompletableFuture or at least send full exception to error handler
        openOfferManager.placeOffer(offer, reservedFundsForOffer,
                fundUsingBisqWallet,
                transaction -> futureResult.complete(offer),
                error -> {
                    if (error.contains("Insufficient money"))
                        futureResult.completeExceptionally(new InsufficientMoneyException(error));
                    else if (error.contains("Amount is larger"))
                        futureResult.completeExceptionally(new AmountTooHighException(error));
                    else
                        futureResult.completeExceptionally(new RuntimeException(error));
                });

        return futureResult;
    }

    @NotNull
    private <T> CompletableFuture<T> failFuture(CompletableFuture<T> futureResult, Exception ex) {
        futureResult.completeExceptionally(ex);
        return futureResult;
    }

    /// START TODO REFACTOR OFFER TAKE DEPENDENCIES //////////////////////////

    public CompletableFuture<Trade> offerTake(String offerId, String paymentAccountId, long amount, boolean useSavingsWallet) {
        final CompletableFuture<Trade> futureResult = new CompletableFuture<>();
        final Offer offer;
        try {
            offer = getOffer(offerId);
        } catch (NotFoundException e) {
            return failFuture(futureResult, e);
        }

        if (offer.getMakerNodeAddress().equals(p2PService.getAddress())) {
            return failFuture(futureResult, new OfferTakerSameAsMakerException("Taker's address same as maker's"));
        }

        // check the paymentAccountId is valid
        final PaymentAccount paymentAccount = getPaymentAccount(paymentAccountId);
        if (paymentAccount == null) {
            return failFuture(futureResult, new PaymentAccountNotFoundException("Could not find payment account with id: " + paymentAccountId));
        }

        // check the paymentAccountId is compatible with the offer
        if (!isPaymentAccountValidForOffer(offer, paymentAccount)) {
            final String errorMessage = "PaymentAccount is not valid for offer, needs " + offer.getCurrencyCode();
            return failFuture(futureResult, new IncompatiblePaymentAccountException(errorMessage));
        }

        // check the amount is within the range
        Coin coinAmount = Coin.valueOf(amount);
        //if(coinAmount.isLessThan(offer.getMinAmount()) || coinAmount.isGreaterThan(offer.getma)

        // workaround because TradeTask does not have an error handler to notify us that something went wrong
        if (btcWalletService.getAvailableBalance().isLessThan(coinAmount)) {
            final String errorMessage = "Available balance " + btcWalletService.getAvailableBalance() + " is less than needed amount: " + coinAmount;
            return failFuture(futureResult, new io.bisq.api.exceptions.InsufficientMoneyException(errorMessage));
        }

        // check that the price is correct ??

        // check taker fee

        // check security deposit for BTC buyer
        // check security deposit for BTC seller

        Coin securityDeposit = offer.getDirection() == OfferPayload.Direction.SELL ?
                offer.getBuyerSecurityDeposit() :
                offer.getSellerSecurityDeposit();
        Coin txFeeFromFeeService = feeService.getTxFee(600);
        Coin fundsNeededForTradeTemp = securityDeposit.add(txFeeFromFeeService).add(txFeeFromFeeService);
        final Coin fundsNeededForTrade;
        if (offer.isBuyOffer())
            fundsNeededForTrade = fundsNeededForTradeTemp.add(coinAmount);
        else
            fundsNeededForTrade = fundsNeededForTradeTemp;

        Coin takerFee = getTakerFee(coinAmount);
        checkNotNull(txFeeFromFeeService, "txFeeFromFeeService must not be null");
        checkNotNull(takerFee, "takerFee must not be null");

        tradeManager.onTakeOffer(coinAmount,
                txFeeFromFeeService,
                takerFee,
                isCurrencyForTakerFeeBtc(coinAmount),
                offer.getPrice().getValue(),
                fundsNeededForTrade,
                offer,
                paymentAccount.getId(),
                useSavingsWallet,
                futureResult::complete,
                error -> futureResult.completeExceptionally(new RuntimeException(error))
        );
        return futureResult;
    }

    boolean isCurrencyForTakerFeeBtc(Coin amount) {
        return preferences.getPayFeeInBtc() || !isBsqForFeeAvailable(amount);
    }

    @Nullable
    Coin getTakerFee(Coin amount, boolean isCurrencyForTakerFeeBtc) {
        if (amount != null) {
            // TODO write unit test for that
            Coin feePerBtc = CoinUtil.getFeePerBtc(FeeService.getTakerFeePerBtc(isCurrencyForTakerFeeBtc), amount);
            return CoinUtil.maxCoin(feePerBtc, FeeService.getMinTakerFee(isCurrencyForTakerFeeBtc));
        } else {
            return null;
        }
    }

    @Nullable
    public Coin getTakerFee(Coin amount) {
        return getTakerFee(amount, isCurrencyForTakerFeeBtc(amount));
    }


    boolean isBsqForFeeAvailable(Coin amount) {
        return BisqEnvironment.isBaseCurrencySupportingBsq() &&
                getTakerFee(amount, false) != null &&
                bsqWalletService.getAvailableBalance() != null &&
                getTakerFee(amount, false) != null &&
                !bsqWalletService.getAvailableBalance().subtract(getTakerFee(amount, false)).isNegative();
    }

    /// STOP TODO REFACTOR OFFER TAKE DEPENDENCIES //////////////////////////

    public List<Trade> getTradeList() {
        final ObservableList<Trade> tradableList = tradeManager.getTradableList();
        if (null != tradableList) return tradableList.sorted();
        return Collections.emptyList();
    }

    public List<ClosedTradableDetails> getClosedTradableList() {
        final ClosedTradableConverter closedTradableConverter = injector.getInstance(ClosedTradableConverter.class);
        return closedTradableManager.getClosedTradables().stream()
                .sorted((o1, o2) -> o2.getDate().compareTo(o1.getDate()))
                .map(closedTradableConverter::convert)
                .collect(toList());
    }

    public Trade getTrade(String tradeId) {
        final String safeTradeId = (null == tradeId) ? "" : tradeId;
        final Optional<Trade> tradeOptional = getTradeList().stream().filter(item -> safeTradeId.equals(item.getId())).findAny();
        if (!tradeOptional.isPresent()) {
            throw new NotFoundException("Trade not found: " + tradeId);
        }
        return tradeOptional.get();
    }

    public WalletDetails getWalletDetails() {
        if (!btcWalletService.isWalletReady()) {
            throw new WalletNotReadyException("Wallet is not ready");
        }

        Coin availableBalance = btcWalletService.getAvailableBalance();
        Coin reservedBalance = updateReservedBalance();
        Coin lockedBalance = updateLockedBalance();
        return new WalletDetails(availableBalance.getValue(), reservedBalance.getValue(), lockedBalance.getValue());
    }

    // TODO copied from MainViewModel - refactor !
    private Coin updateLockedBalance() {
        Stream<Trade> lockedTrades = Stream.concat(closedTradableManager.getLockedTradesStream(), failedTradesManager.getLockedTradesStream());
        lockedTrades = Stream.concat(lockedTrades, tradeManager.getLockedTradesStream());
        Coin sum = Coin.valueOf(lockedTrades
                .mapToLong(trade -> {
                    final Optional<AddressEntry> addressEntryOptional = btcWalletService.getAddressEntry(trade.getId(), AddressEntry.Context.MULTI_SIG);
                    if (addressEntryOptional.isPresent())
                        return addressEntryOptional.get().getCoinLockedInMultiSig().getValue();
                    else
                        return 0;
                })
                .sum());
        return sum;
    }

    // TODO copied from MainViewModel - refactor !
    private Coin updateReservedBalance() {
        Coin sum = Coin.valueOf(openOfferManager.getObservableList().stream()
                .map(openOffer -> {
                    final Optional<AddressEntry> addressEntryOptional = btcWalletService.getAddressEntry(openOffer.getId(), AddressEntry.Context.RESERVED_FOR_TRADE);
                    if (addressEntryOptional.isPresent()) {
                        Address address = addressEntryOptional.get().getAddress();
                        return btcWalletService.getBalanceForAddress(address);
                    } else {
                        return null;
                    }
                })
                .filter(e -> e != null)
                .mapToLong(Coin::getValue)
                .sum());

        return sum;
    }


    public WalletTransactionList getWalletTransactions() {
        final Wallet wallet = walletsSetup.getBtcWallet();
        WalletTransactionList walletTransactions = new WalletTransactionList();
        walletTransactions.transactions.addAll(btcWalletService.getTransactions(true)
                .stream()
                .map(transaction -> toWalletTransaction(wallet, transaction))
                .collect(Collectors.toList()));
        walletTransactions.total = walletTransactions.transactions.size();
        return walletTransactions;
    }

    @NotNull
    private WalletTransaction toWalletTransaction(Wallet wallet, Transaction transaction) {
        final Coin valueSentFromMe = transaction.getValueSentFromMe(wallet);
        final Coin valueSentToMe = transaction.getValueSentToMe(wallet);
        boolean received = false;
        String addressString = null;

        if (valueSentToMe.isZero()) {
            for (TransactionOutput output : transaction.getOutputs()) {
                if (!btcWalletService.isTransactionOutputMine(output)) {
                    received = false;
                    if (WalletService.isOutputScriptConvertibleToAddress(output)) {
                        addressString = WalletService.getAddressStringFromOutput(output);
                        break;
                    }
                }
            }
        } else if (valueSentFromMe.isZero()) {
            received = true;
            for (TransactionOutput output : transaction.getOutputs()) {
                if (btcWalletService.isTransactionOutputMine(output) &&
                        WalletService.isOutputScriptConvertibleToAddress(output)) {
                    addressString = WalletService.getAddressStringFromOutput(output);
                    break;
                }
            }
        } else {
            boolean outgoing = false;
            for (TransactionOutput output : transaction.getOutputs()) {
                if (!btcWalletService.isTransactionOutputMine(output)) {
                    if (WalletService.isOutputScriptConvertibleToAddress(output)) {
                        addressString = WalletService.getAddressStringFromOutput(output);
                        outgoing = !(BisqEnvironment.isBaseCurrencySupportingBsq() && bsqWalletService.isTransactionOutputMine(output));
                        break;
                    }
                }
            }

            if (outgoing) {
                received = false;
            }
        }
        final TransactionConfidence confidence = transaction.getConfidence();
        int confirmations = null == confidence ? 0 : confidence.getDepthInBlocks();

        final WalletTransaction walletTransaction = new WalletTransaction();
        walletTransaction.updateTime = transaction.getUpdateTime().getTime();
        walletTransaction.hash = transaction.getHashAsString();
        walletTransaction.fee = (transaction.getFee() == null) ? -1 : transaction.getFee().value;
        walletTransaction.value = transaction.getValue(wallet).value;
        walletTransaction.valueSentFromMe = valueSentFromMe.value;
        walletTransaction.valueSentToMe = valueSentToMe.value;
        walletTransaction.confirmations = confirmations;
        walletTransaction.inbound = received;
        walletTransaction.address = addressString;
        return walletTransaction;
    }

    public WalletAddressList getWalletAddresses(WalletAddressPurpose purpose) {
        final Stream<AddressEntry> addressEntryStream;
        if (WalletAddressPurpose.SEND_FUNDS.equals(purpose)) {
            addressEntryStream = tradeManager.getAddressEntriesForAvailableBalanceStream();
        } else if (WalletAddressPurpose.RESERVED_FUNDS.equals(purpose)) {
            addressEntryStream = getReservedFundsAddressEntryStream();
        } else if (WalletAddressPurpose.LOCKED_FUNDS.equals(purpose)) {
            addressEntryStream = getLockedFundsAddressEntryStream();
        } else if (WalletAddressPurpose.RECEIVE_FUNDS.equals(purpose)) {
            addressEntryStream = btcWalletService.getAvailableAddressEntries().stream();
        } else {
            addressEntryStream = btcWalletService.getAddressEntryListAsImmutableList().stream();
        }
        final List<WalletAddress> walletAddresses = addressEntryStream
                .map(entry -> convertAddressEntryToWalletAddress(entry, btcWalletService))
                .collect(toList());
        final WalletAddressList walletAddressList = new WalletAddressList();
        walletAddressList.walletAddresses = walletAddresses;
        walletAddressList.total = walletAddresses.size();
        return walletAddressList;
    }

    public void withdrawFunds(Set<String> sourceAddresses, Coin amountAsCoin, boolean feeExcluded, String targetAddress)
            throws AddressEntryException, InsufficientFundsException, AmountTooLowException {
        // get all address entries
        final Supplier<Stream<AddressEntry>> addressEntryStreamSupplier = () -> sourceAddresses.stream()
                .filter(address -> null != address)
                .map(address -> btcWalletService.getAddressEntryListAsImmutableList().stream().filter(addressEntry -> address.equals(addressEntry.getAddressString())).findFirst().orElse(null))
                .filter(item -> null != item);
        // this filter matches all unauthorized address types
        Predicate<AddressEntry> filterNotAllowedAddressEntries = addressEntry -> {
            return !(AddressEntry.Context.AVAILABLE.equals(addressEntry.getContext())
                    || AddressEntry.Context.TRADE_PAYOUT.equals(addressEntry.getContext()));
        };
        // check if there are any unauthorized address types
        if (addressEntryStreamSupplier.get().anyMatch(filterNotAllowedAddressEntries)) {
            throw new AddressEntryException("Only addresses with context AVAILABLE and TRADE_PAYOUT can be used:"
                    + addressEntryStreamSupplier.get().filter(filterNotAllowedAddressEntries).map(addressEntry -> addressEntry.getAddressString()).collect(Collectors.joining(", ")));
        }

        Coin sendersAmount;
        // We do not know sendersAmount if senderPaysFee is true. We repeat fee calculation after first attempt if senderPaysFee is true.
        Transaction feeEstimationTransaction;
        try {
            feeEstimationTransaction = btcWalletService.getFeeEstimationTransactionForMultipleAddresses(sourceAddresses, amountAsCoin);
        } catch (IllegalArgumentException e) {
            if (e.getMessage().contains("dust limit")) {
                throw new AmountTooLowException(e.getMessage());
            }
            throw e;
        }
        if (feeExcluded && feeEstimationTransaction != null) {
            sendersAmount = amountAsCoin.add(feeEstimationTransaction.getFee());
            feeEstimationTransaction = btcWalletService.getFeeEstimationTransactionForMultipleAddresses(sourceAddresses, sendersAmount);
        }
        checkNotNull(feeEstimationTransaction, "feeEstimationTransaction must not be null");
        Coin fee = feeEstimationTransaction.getFee();
        sendersAmount = feeExcluded ? amountAsCoin.add(fee) : amountAsCoin;
        Coin receiverAmount = feeExcluded ? amountAsCoin : amountAsCoin.subtract(fee);

        final Coin totalAvailableAmountOfSelectedItems = addressEntryStreamSupplier.get()
                .map(address -> btcWalletService.getBalanceForAddress(address.getAddress()))
                .reduce(Coin.ZERO, Coin::add);

        if (!sendersAmount.isPositive())
            throw new ValidationException("Senders amount must be positive");
        if (!new BtcAddressValidator().validate(targetAddress).isValid)
            throw new ValidationException("Invalid target address");
        if (sourceAddresses.isEmpty())
            throw new ValidationException("List of source addresses must not be empty");
        if (sendersAmount.compareTo(totalAvailableAmountOfSelectedItems) > 0)
            throw new InsufficientFundsException("Not enough funds in selected addresses");

        if (receiverAmount.isPositive()) {
            try {
//                TODO return completable future
                btcWalletService.sendFundsForMultipleAddresses(sourceAddresses, targetAddress, amountAsCoin, fee, null, null, new FutureCallback<Transaction>() {
                    @Override
                    public void onSuccess(@javax.annotation.Nullable Transaction transaction) {
                        if (transaction != null) {
                            log.debug("onWithdraw onSuccess tx ID:" + transaction.getHashAsString());
                        } else {
                            log.error("onWithdraw transaction is null");
                        }

                        List<Trade> trades = new ArrayList<>(tradeManager.getTradableList());
                        trades.stream()
                                .filter(Trade::isPayoutPublished)
                                .forEach(trade -> btcWalletService.getAddressEntry(trade.getId(), AddressEntry.Context.TRADE_PAYOUT)
                                        .ifPresent(addressEntry -> {
                                            if (btcWalletService.getBalanceForAddress(addressEntry.getAddress()).isZero())
                                                tradeManager.addTradeToClosedTrades(trade);
                                        }));
                    }

                    @Override
                    public void onFailure(@NotNull Throwable t) {
                        log.error("onWithdraw onFailure");
                    }
                });
            } catch (org.bitcoinj.core.InsufficientMoneyException e) {
                throw new InsufficientFundsException(e.getMessage());
            }
        } else {
            throw new AmountTooLowException(Res.get("portfolio.pending.step5_buyer.amountTooLow"));
        }
    }

    private Stream<AddressEntry> getReceiveOrSendFundsAddressEntryStream() {
        return tradeManager.getLockedTradesStream()
                .map(trade -> {
                    final Optional<AddressEntry> addressEntryOptional = btcWalletService.getAddressEntry(trade.getId(), AddressEntry.Context.MULTI_SIG);
                    return addressEntryOptional.isPresent() ? addressEntryOptional.get() : null;
                })
                .filter(e -> e != null);
    }

    private Stream<AddressEntry> getLockedFundsAddressEntryStream() {
        return tradeManager.getLockedTradesStream()
                .map(trade -> {
                    final Optional<AddressEntry> addressEntryOptional = btcWalletService.getAddressEntry(trade.getId(), AddressEntry.Context.MULTI_SIG);
                    return addressEntryOptional.isPresent() ? addressEntryOptional.get() : null;
                })
                .filter(e -> e != null);
    }

    private Stream<AddressEntry> getReservedFundsAddressEntryStream() {
        return openOfferManager.getObservableList().stream()
                .map(openOffer -> {
                    Optional<AddressEntry> addressEntryOptional = btcWalletService.getAddressEntry(openOffer.getId(), AddressEntry.Context.RESERVED_FOR_TRADE);
                    return addressEntryOptional.isPresent() ? addressEntryOptional.get() : null;
                })
                .filter(e -> e != null);
    }

    public CompletableFuture<Void> paymentStarted(String tradeId) {
        final CompletableFuture<Void> futureResult = new CompletableFuture<>();
        Trade trade;
        try {
            trade = getTrade(tradeId);
        } catch (NotFoundException e) {
            return failFuture(futureResult, e);
        }

        if (!Trade.State.DEPOSIT_CONFIRMED_IN_BLOCK_CHAIN.equals(trade.getState())) {
            return failFuture(futureResult, new ValidationException("Trade is not in the correct state to start payment: " + trade.getState()));
        }
        TradeProtocol tradeProtocol = trade.getTradeProtocol();
        ResultHandler resultHandler = () -> futureResult.complete(null);
        ErrorMessageHandler errorResultHandler = message -> futureResult.completeExceptionally(new RuntimeException(message));

        if (trade instanceof BuyerAsMakerTrade) {
            ((BuyerAsMakerProtocol) tradeProtocol).onFiatPaymentStarted(resultHandler, errorResultHandler);
        } else {
            ((BuyerAsTakerProtocol) tradeProtocol).onFiatPaymentStarted(resultHandler, errorResultHandler);
        }
        return futureResult;
    }

    public CompletableFuture<Void> paymentReceived(String tradeId) {
        final CompletableFuture<Void> futureResult = new CompletableFuture<>();
        Trade trade;
        try {
            trade = getTrade(tradeId);
        } catch (NotFoundException e) {
            return failFuture(futureResult, e);
        }

        if (!Trade.State.SELLER_RECEIVED_FIAT_PAYMENT_INITIATED_MSG.equals(trade.getState())) {
            return failFuture(futureResult, new ValidationException("Trade is not in the correct state to receive payment: " + trade.getState()));
        }
        TradeProtocol tradeProtocol = trade.getTradeProtocol();

        if (!(tradeProtocol instanceof SellerAsTakerProtocol || tradeProtocol instanceof SellerAsMakerProtocol)) {
            return failFuture(futureResult, new ValidationException("Trade is not in the correct state to receive payment: " + trade.getState()));
        }

        ResultHandler resultHandler = () -> futureResult.complete(null);
        ErrorMessageHandler errorResultHandler = message -> futureResult.completeExceptionally(new RuntimeException(message));

//        TODO I think we should check instance of tradeProtocol here instead of trade
        if (trade instanceof SellerAsMakerTrade) {
            ((SellerAsMakerProtocol) tradeProtocol).onFiatPaymentReceived(resultHandler, errorResultHandler);
        } else {
            ((SellerAsTakerProtocol) tradeProtocol).onFiatPaymentReceived(resultHandler, errorResultHandler);
        }
        return futureResult;
    }

    public void moveFundsToBisqWallet(String tradeId) {
        final Trade trade = getTrade(tradeId);
        final Trade.State tradeState = trade.getState();
        if (!Trade.State.SELLER_SAW_ARRIVED_PAYOUT_TX_PUBLISHED_MSG.equals(tradeState) && !Trade.State.BUYER_RECEIVED_PAYOUT_TX_PUBLISHED_MSG.equals(tradeState))
            throw new ValidationException("Trade is not in the correct state to transfer funds out: " + tradeState);
        btcWalletService.swapTradeEntryToAvailableEntry(trade.getId(), AddressEntry.Context.TRADE_PAYOUT);
        // TODO do we need to handle this ui stuff? --> handleTradeCompleted();
        tradeManager.addTradeToClosedTrades(trade);
    }

    public void registerArbitrator(List<String> languageCodes) {
//        TODO most of this code is dupplication of ArbitratorRegistrationViewModel.onRegister
        final String privKeyString = useDevPrivilegeKeys ? DevEnv.DEV_PRIVILEGE_PRIV_KEY : null;
        //        TODO hm, are we going to send private key over http?
        if (null == privKeyString) {
            throw new RuntimeException("Missing private key");
        }
        ECKey registrationKey = arbitratorManager.getRegistrationKey(privKeyString);
        if (null == registrationKey) {
            throw new RuntimeException("Missing registration key");
        }
        AddressEntry arbitratorDepositAddressEntry = btcWalletService.getOrCreateAddressEntry(AddressEntry.Context.ARBITRATOR);
        String registrationSignature = arbitratorManager.signStorageSignaturePubKey(registrationKey);
        Arbitrator arbitrator = new Arbitrator(
                p2PService.getAddress(),
                arbitratorDepositAddressEntry.getPubKey(),
                arbitratorDepositAddressEntry.getAddressString(),
                keyRing.getPubKeyRing(),
                new ArrayList<>(languageCodes),
                new Date().getTime(),
                registrationKey.getPubKey(),
                registrationSignature,
                null,
                null,
                null
        );
//        TODO I don't know how to deal with those callbacks in order to send response back
        arbitratorManager.addArbitrator(arbitrator, () -> System.out.println("Arbi registered"), message -> System.out.println("Error when registering arbi: " + message));
    }

    public Collection<Arbitrator> getArbitrators(boolean acceptedOnly) {
        if (acceptedOnly) {
            return user.getAcceptedArbitrators();
        }
        return arbitratorManager.getArbitratorsObservableMap().values();
    }

    public Collection<Arbitrator> selectArbitrator(String arbitratorAddress) {
        final Arbitrator arbitrator = getArbitratorByAddress(arbitratorAddress);
        if (null == arbitrator) {
            throw new NotFoundException("Arbitrator not found: " + arbitratorAddress);
        }
        if (!arbitratorIsTrader(arbitrator)) {
            user.addAcceptedArbitrator(arbitrator);
            user.addAcceptedMediator(ArbitratorManager.getMediator(arbitrator));
            return user.getAcceptedArbitrators();
        }
        throw new ValidationException("You cannot select yourself as an arbitrator");
    }

    public Collection<Arbitrator> deselectArbitrator(String arbitratorAddress) {
        final Arbitrator arbitrator = getArbitratorByAddress(arbitratorAddress);
        if (null == arbitrator) {
            throw new NotFoundException("Arbitrator not found: " + arbitratorAddress);
        }
        user.removeAcceptedArbitrator(arbitrator);
        user.removeAcceptedMediator(ArbitratorManager.getMediator(arbitrator));
        return user.getAcceptedArbitrators();
    }

    private Arbitrator getArbitratorByAddress(String arbitratorAddress) {
        return arbitratorManager.getArbitratorsObservableMap().get(new NodeAddress(arbitratorAddress));
    }

    private boolean arbitratorIsTrader(Arbitrator arbitrator) {
        return keyRing.getPubKeyRing().equals(arbitrator.getPubKeyRing());
    }

    public WalletAddress getOrCreateAvailableUnusedWalletAddresses() {
        final AddressEntry entry = btcWalletService.getOrCreateUnusedAddressEntry(AddressEntry.Context.AVAILABLE);
        return convertAddressEntryToWalletAddress(entry, btcWalletService);
    }

    public P2PNetworkStatus getP2PNetworkStatus() {
        final P2PNetworkStatus p2PNetworkStatus = new P2PNetworkStatus();
        final NodeAddress address = p2PService.getAddress();
        if (null != address)
            p2PNetworkStatus.address = address.getFullAddress();
        p2PNetworkStatus.p2pNetworkConnection = p2PService.getNetworkNode().getAllConnections().stream()
                .map(P2PNetworkConnection::new)
                .collect(Collectors.toList());
        p2PNetworkStatus.totalSentBytes = Statistic.totalSentBytesProperty().get();
        p2PNetworkStatus.totalReceivedBytes = Statistic.totalReceivedBytesProperty().get();
        return p2PNetworkStatus;
    }

    public BitcoinNetworkStatus getBitcoinNetworkStatus() {
        final BitcoinNetworkStatus networkStatus = new BitcoinNetworkStatus();
        final List<Peer> peers = walletsSetup.connectedPeersProperty().get();
        if (null != peers)
            networkStatus.peers = peers.stream().map(peer -> peer.getAddress().toString()).collect(Collectors.toList());
        else
            networkStatus.peers = Collections.emptyList();
        networkStatus.useTorForBitcoinJ = preferences.getUseTorForBitcoinJ();
        networkStatus.bitcoinNodesOption = BitcoinNodes.BitcoinNodesOption.values()[preferences.getBitcoinNodesOptionOrdinal()];
        networkStatus.bitcoinNodes = preferences.getBitcoinNodes();
        return networkStatus;
    }

    public Preferences getPreferences() {
        final Preferences preferences = new Preferences();
        preferences.autoSelectArbitrators = this.preferences.isAutoSelectArbitrators();
        preferences.baseCurrencyNetwork = BisqEnvironment.getBaseCurrencyNetwork().getCurrencyCode();
        preferences.blockChainExplorer = this.preferences.getBlockChainExplorer().name;
        preferences.cryptoCurrencies = tradeCurrenciesToCodes(this.preferences.getCryptoCurrencies());
        preferences.fiatCurrencies = tradeCurrenciesToCodes(this.preferences.getFiatCurrencies());
        preferences.ignoredTraders = this.preferences.getIgnoreTradersList();
        preferences.maxPriceDistance = this.preferences.getMaxPriceDistanceInPercent();
        preferences.preferredTradeCurrency = this.preferences.getPreferredTradeCurrency().getCode();
        preferences.useCustomWithdrawalTxFee = this.preferences.getUseCustomWithdrawalTxFeeProperty().get();
        final Country userCountry = this.preferences.getUserCountry();
        if (null != userCountry)
            preferences.userCountry = userCountry.code;
        preferences.userLanguage = this.preferences.getUserLanguage();
        preferences.withdrawalTxFee = this.preferences.getWithdrawalTxFeeInBytes();
        return preferences;
    }

    public PreferencesAvailableValues getPreferencesAvailableValues() {
        final PreferencesAvailableValues availableValues = new PreferencesAvailableValues();
        availableValues.blockChainExplorers = preferences.getBlockChainExplorers().stream().map(i -> i.name).collect(Collectors.toList());
        availableValues.cryptoCurrencies = tradeCurrenciesToCodes(CurrencyUtil.getAllSortedCryptoCurrencies());
        availableValues.fiatCurrencies = tradeCurrenciesToCodes(CurrencyUtil.getAllSortedFiatCurrencies());
        availableValues.userCountries = CountryUtil.getAllCountries().stream().map(i -> i.code).collect(Collectors.toList());
        return availableValues;
    }

    public Preferences setPreferences(Preferences update) {
        if (null != update.autoSelectArbitrators) {
            preferences.setAutoSelectArbitrators(update.autoSelectArbitrators);
        }
        if (null != update.baseCurrencyNetwork && !update.baseCurrencyNetwork.equals(BisqEnvironment.getBaseCurrencyNetwork().getCurrencyCode())) {
            throw new ValidationException("Changing baseCurrencyNetwork is not supported");
        }
        if (null != update.blockChainExplorer) {
            final Optional<BlockChainExplorer> explorerOptional = preferences.getBlockChainExplorers().stream().filter(i -> update.blockChainExplorer.equals(i.name)).findAny();
            if (!explorerOptional.isPresent()) {
                throw new ValidationException("Unsupported value of blockChainExplorer: " + update.blockChainExplorer);
            }
            preferences.setBlockChainExplorer(explorerOptional.get());
            preferences.setBlockChainExplorer(explorerOptional.get());
        }
        if (null != update.cryptoCurrencies) {
            final List<CryptoCurrency> cryptoCurrencies = preferences.getCryptoCurrencies();
            final Collection<CryptoCurrency> convertedCryptos = codesToCryptoCurrencies(update.cryptoCurrencies);
            cryptoCurrencies.clear();
            cryptoCurrencies.addAll(convertedCryptos);
        }
        if (null != update.fiatCurrencies) {
            final List<FiatCurrency> fiatCurrencies = preferences.getFiatCurrencies();
            final Collection<FiatCurrency> convertedFiat = codesToFiatCurrencies(update.fiatCurrencies);
            fiatCurrencies.clear();
            fiatCurrencies.addAll(convertedFiat);
        }
        if (null != update.ignoredTraders) {
            preferences.setIgnoreTradersList(update.ignoredTraders.stream().map(i -> i.replace(":9999", "").replace(".onion", "")).collect(Collectors.toList()));
        }
        if (null != update.maxPriceDistance) {
            preferences.setMaxPriceDistanceInPercent(update.maxPriceDistance);
        }
        if (null != update.preferredTradeCurrency) {
            preferences.setPreferredTradeCurrency(codeToTradeCurrency(update.preferredTradeCurrency));
        }
        if (null != update.useCustomWithdrawalTxFee) {
            preferences.setUseCustomWithdrawalTxFee(update.useCustomWithdrawalTxFee);
        }
        if (null != update.userCountry) {
            preferences.setUserCountry(codeToCountry(update.userCountry));
        }
        if (null != update.userLanguage) {
            preferences.setUserLanguage(update.userLanguage);
        }
        if (null != update.withdrawalTxFee) {
            preferences.setWithdrawalTxFeeInBytes(update.withdrawalTxFee);
        }
        return getPreferences();
    }

    public VersionDetails getVersionDetails() {
        final VersionDetails versionDetails = new VersionDetails();
        versionDetails.application = Version.VERSION;
        versionDetails.network = Version.P2P_NETWORK_VERSION;
        versionDetails.p2PMessage = Version.getP2PMessageVersion();
        versionDetails.localDB = Version.LOCAL_DB_VERSION;
        versionDetails.tradeProtocol = Version.TRADE_PROTOCOL_VERSION;
        return versionDetails;
    }

    public AuthResult authenticate(String password) {
        final TokenRegistry tokenRegistry = injector.getInstance(TokenRegistry.class);
        final boolean isPasswordValid = btcWalletService.isWalletReady() && btcWalletService.isEncrypted() && isWalletPasswordValid(password);
        if (isPasswordValid) {
            return new AuthResult(tokenRegistry.generateToken());
        }
        throw new UnauthorizedException();
    }

    private boolean isWalletPasswordValid(String password) {
        final KeyParameter aesKey = getAESKey(password);
        return isWalletPasswordValid(aesKey);
    }

    private boolean isWalletPasswordValid(KeyParameter aesKey) {
        final WalletsManager walletsManager = injector.getInstance(WalletsManager.class);
        return null != aesKey && walletsManager.checkAESKey(aesKey);
    }

    private KeyParameter getAESKey(String password) {
        return getAESKeyAndScrypt(password).first;
    }

    private Tuple2<KeyParameter, KeyCrypterScrypt> getAESKeyAndScrypt(String password) {
        final WalletsManager walletsManager = injector.getInstance(WalletsManager.class);
        final KeyCrypterScrypt keyCrypterScrypt = walletsManager.getKeyCrypterScrypt();
        return new Tuple2<>(keyCrypterScrypt.deriveKey(password), keyCrypterScrypt);
    }

    public AuthResult changePassword(String oldPassword, String newPassword) {
        if (!btcWalletService.isWalletReady())
            throw new WalletNotReadyException("Wallet not ready yet");
        final WalletsManager walletsManager = injector.getInstance(WalletsManager.class);
        if (btcWalletService.isEncrypted()) {
            final KeyParameter aesKey = null == oldPassword ? null : getAESKey(oldPassword);
            if (!isWalletPasswordValid(aesKey))
                throw new UnauthorizedException();
            walletsManager.decryptWallets(aesKey);
        }
        if (null != newPassword && newPassword.length() > 0) {
            final Tuple2<KeyParameter, KeyCrypterScrypt> aesKeyAndScrypt = getAESKeyAndScrypt(newPassword);
            walletsManager.encryptWallets(aesKeyAndScrypt.second, aesKeyAndScrypt.first);
            final TokenRegistry tokenRegistry = injector.getInstance(TokenRegistry.class);
            tokenRegistry.clear();
            return new AuthResult(tokenRegistry.generateToken());
        }
        return null;
    }

    public PriceFeed getPriceFeed(String[] codes) {
        final PriceFeedService priceFeedService = injector.getInstance(PriceFeedService.class);
        final List<FiatCurrency> fiatCurrencies = preferences.getFiatCurrencies();
        final List<CryptoCurrency> cryptoCurrencies = preferences.getCryptoCurrencies();
        final Stream<String> codesStream;
        if (null == codes || 0 == codes.length)
            codesStream = Stream.concat(fiatCurrencies.stream(), cryptoCurrencies.stream()).map(TradeCurrency::getCode);
        else
            codesStream = Arrays.asList(codes).stream();
        final List<MarketPrice> marketPrices = codesStream
                .map(priceFeedService::getMarketPrice)
                .filter(i -> null != i)
                .collect(toList());
        final PriceFeed priceFeed = new PriceFeed();
        for (MarketPrice price : marketPrices)
            priceFeed.prices.put(price.getCurrencyCode(), price.getPrice());
        return priceFeed;
    }

    public enum WalletAddressPurpose {
        LOCKED_FUNDS,
        RECEIVE_FUNDS,
        RESERVED_FUNDS,
        SEND_FUNDS
    }

    @NotNull
    private static Country codeToCountry(String code) {
        final Optional<Country> countryOptional = CountryUtil.findCountryByCode(code);
        if (!countryOptional.isPresent())
            throw new ValidationException("Unsupported country code: " + code);
        return countryOptional.get();
    }

    @NotNull
    private Collection<CryptoCurrency> codesToCryptoCurrencies(List<String> cryptoCurrencies) {
        return cryptoCurrencies.stream().map(code -> {
            final Optional<CryptoCurrency> cryptoCurrency = CurrencyUtil.getCryptoCurrency(code);
            if (!cryptoCurrency.isPresent())
                throw new ValidationException("Unsupported crypto currency code: " + code);
            return cryptoCurrency.get();
        }).collect(Collectors.toList());
    }

    @NotNull
    private Collection<FiatCurrency> codesToFiatCurrencies(List<String> fiatCurrencies) {
        return fiatCurrencies.stream().map(code -> {
            final Optional<FiatCurrency> cryptoCurrency = CurrencyUtil.getFiatCurrency(code);
            if (!cryptoCurrency.isPresent())
                throw new ValidationException("Unsupported fiat currency code: " + code);
            return cryptoCurrency.get();
        }).collect(Collectors.toList());
    }

    @NotNull
    private static TradeCurrency codeToTradeCurrency(String code) {
        final Optional<TradeCurrency> currencyOptional = CurrencyUtil.getTradeCurrency(code);
        if (!currencyOptional.isPresent())
            throw new ValidationException("Unsupported trade currency code: " + code);
        return currencyOptional.get();
    }

    @NotNull
    private static WalletAddress convertAddressEntryToWalletAddress(AddressEntry entry, BtcWalletService btcWalletService) {
        final Coin balance;
        if (AddressEntry.Context.MULTI_SIG.equals(entry.getContext())) {
            balance = entry.getCoinLockedInMultiSig();
        } else {
            balance = btcWalletService.getBalanceForAddress(entry.getAddress());
        }
        final TransactionConfidence confidence = btcWalletService.getConfidenceForAddress(entry.getAddress());
        final int confirmations = null == confidence ? 0 : confidence.getDepthInBlocks();
        return new WalletAddress(entry.getAddressString(), balance.getValue(), confirmations, entry.getContext(), entry.getOfferId());
    }

    private static List<String> tradeCurrenciesToCodes(Collection<? extends TradeCurrency> tradeCurrencies) {
        return tradeCurrencies.stream().map(TradeCurrency::getCode).collect(Collectors.toList());
    }
}
