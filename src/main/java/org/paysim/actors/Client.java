package org.paysim.actors;

import ec.util.MersenneTwisterFast;
import org.paysim.PaySimState;
import org.paysim.base.ClientActionProfile;
import org.paysim.base.ClientProfile;
import org.paysim.base.StepActionProfile;
import org.paysim.base.Transaction;
import org.paysim.identity.Identity;
import org.paysim.parameters.ActionTypes;
import org.paysim.parameters.BalancesClients;
import org.paysim.utils.RandomCollection;
import sim.engine.SimState;
import sim.engine.Steppable;
import sim.util.distribution.Binomial;

import java.util.*;

import static java.lang.Math.max;


public class Client extends SuperActor implements Steppable {
    private static final String CLIENT_IDENTIFIER = "C";
    private static final int MIN_NB_TRANSFER_FOR_FRAUD = 3;
    protected static final String CASH_IN = "CASH_IN", CASH_OUT = "CASH_OUT", DEBIT = "DEBIT",
            PAYMENT = "PAYMENT", TRANSFER = "TRANSFER", DEPOSIT = "DEPOSIT";
    private final Bank bank;
    private final ClientProfile clientProfile;
    private final double clientWeight;
    private double balanceMax = 0;
    private int countTransferTransactions = 0;
    private double expectedAvgTransaction = 0;
    private double initialBalance;
    private final Identity identity;

    Client(String id, PaySimState state, Identity identity) {
        super(id, state);
        this.identity = identity;

        this.bank = state.pickRandomBank();
        this.clientProfile = new ClientProfile(state.pickNextClientProfile(), state.getRNG());
        this.clientWeight = ((double) clientProfile.getClientTargetCount()) /  state.getParameters().stepsProfiles.getTotalTargetCount();
        this.initialBalance = BalancesClients.pickNextBalance(state.getRNG());
        this.balance = initialBalance;
        this.overdraftLimit = pickOverdraftLimit(state.getRNG());

        // For now, we materialize the Identity into the property map
        this.setProperty(Properties.PHONE, identity.phoneNumber);
        this.setProperty(Properties.NAME, identity.name);
        this.setProperty(Properties.EMAIL, identity.email);
    }

    public Client(PaySimState state, Identity identity) {
        this(state.generateUniqueClientId(), state, identity);
    }

    public Client(PaySimState state) {
        this(state, state.generateIdentity());
    }

    public Identity getIdentity() {
        return identity;
    }

    //-------------------------------------------------------------------------------
    // XXX Most of the below is from the original PaySim codebase

    @Override
    public void step(SimState state) {
        PaySimState paySim = (PaySimState) state;
        int stepTargetCount = paySim.getStepTargetCount();
        if (stepTargetCount > 0) {
            MersenneTwisterFast random = paySim.random;
            int step = (int) state.schedule.getSteps();
            Map<String, Double> stepActionProfile = paySim.getStepProbabilities();

            int count = pickCount(random, stepTargetCount);

            for (int t = 0; t < count; t++) {
                String action = pickAction(random, stepActionProfile);
                StepActionProfile stepAmountProfile = paySim.getStepAction(action);
                double amount = pickAmount(random, action, stepAmountProfile);

                List<Transaction> transactions = makeTransaction(paySim, step, action, amount);
                if (!paySim.onTransactions(transactions)) {
                    // XXX: For now, let's try just returning as a clean way to slowly abort
                    return;
                }
            }
        }
    }

    private int pickCount(MersenneTwisterFast random, int targetStepCount) {
        // B(n,p): n = targetStepCount & p = clientWeight
        Binomial transactionNb = new Binomial(targetStepCount, clientWeight, random);
        return transactionNb.nextInt();
    }

    private String pickAction(MersenneTwisterFast random, Map<String, Double> stepActionProb) {
        Map<String, Double> clientProbabilities = clientProfile.getActionProbability();
        Map<String, Double> rawProbabilities = new HashMap<>();
        RandomCollection<String> actionPicker = new RandomCollection<>(random);

        // Pick the compromise between the Step distribution and the Client distribution
        for (Map.Entry<String, Double> clientEntry : clientProbabilities.entrySet()) {
            String action = clientEntry.getKey();
            double clientProbability = clientEntry.getValue();
            double rawProbability;

            if (stepActionProb.containsKey(action)) {
                double stepProbability = stepActionProb.get(action);

                rawProbability = (clientProbability + stepProbability) / 2;
            } else {
                rawProbability = clientProbability;
            }
            rawProbabilities.put(action, rawProbability);
        }

        // Correct the distribution so the balance of the account do not diverge too much
        double probInflow = 0;
        for (Map.Entry<String, Double> rawEntry : rawProbabilities.entrySet()) {
            String action = rawEntry.getKey();
            if (isInflow(action)) {
                probInflow += rawEntry.getValue();
            }
        }
        double probOutflow = 1 - probInflow;
        double newProbInflow = computeProbWithSpring(probInflow, probOutflow, balance);
        double newProbOutflow = 1 - newProbInflow;

        for (Map.Entry<String, Double> rawEntry : rawProbabilities.entrySet()) {
            String action = rawEntry.getKey();
            double rawProbability = rawEntry.getValue();
            double finalProbability;

            if (isInflow(action)) {
                finalProbability = rawProbability * newProbInflow / probInflow;
            } else {
                finalProbability = rawProbability * newProbOutflow / probOutflow;
            }
            actionPicker.add(finalProbability, action);
        }

        return actionPicker.next();
    }

    /**
     *  The Biased Bernoulli Walk we were doing can go far to the equilibrium of an account
     *  To avoid this we conceptually add a spring that would be attached to the equilibrium position of the account
     */
    private double computeProbWithSpring(double probUp, double probDown, double currentBalance){
        double equilibrium = 40 * expectedAvgTransaction; // Could also be the initial balance in other models
        double correctionStrength = 3 * Math.pow(10, -5); // In a physical model it would be 1 / 2 * kB * T
        double characteristicLengthSpring = equilibrium;
        double k = 1 / characteristicLengthSpring;
        double springForce = k * (equilibrium - currentBalance);
        double newProbUp = 0.5d * ( 1d + (expectedAvgTransaction * correctionStrength) * springForce + (probUp - probDown));

        if (newProbUp > 1){
           newProbUp = 1;
        } else if (newProbUp < 0){
            newProbUp = 0;
        }
        return newProbUp;

    }

    private boolean isInflow(String action){
        String[] inflowActions = {CASH_IN, DEPOSIT};
        return Arrays.stream(inflowActions)
                .anyMatch(action::equals);
    }

    private double pickAmount(MersenneTwisterFast random, String action, StepActionProfile stepAmountProfile) {
        ClientActionProfile clientAmountProfile = clientProfile.getProfilePerAction(action);

        double average, std;
        if (stepAmountProfile != null) {
            // We take the mean between the two distributions
            average = (clientAmountProfile.getAvgAmount() + stepAmountProfile.getAvgAmount()) / 2;
            std = Math.sqrt((Math.pow(clientAmountProfile.getStdAmount(), 2) + Math.pow(stepAmountProfile.getStdAmount(), 2))) / 2;
        } else {
            average = clientAmountProfile.getAvgAmount();
            std = clientAmountProfile.getStdAmount();
        }

        double amount = -1;
        while (amount <= 0) {
            amount = random.nextGaussian() * std + average;
        }

        return amount;
    }

    private List<Transaction> makeTransaction(PaySimState state, int step, String action, double amount) {
        ArrayList<Transaction> transactions = new ArrayList<>();

        switch (action) {
            case CASH_IN:
                transactions.add(handleCashIn(state, step, amount));
                break;
            case CASH_OUT:
                transactions.add(handleCashOut(state, step, amount));
                break;
            case DEBIT:
                transactions.add(handleDebit(state, step, amount));
                break;
            case PAYMENT:
                transactions.add(handlePayment(state, step, amount));
                break;
            case TRANSFER:
                Client clientTo = state.pickRandomClient(getId());
                double reducedAmount = amount;
                boolean lastTransferFailed = false;

                // For transfer transaction there is a limit so we have to split big transactions in smaller chunks
                while (reducedAmount > parameters.transferLimit && !lastTransferFailed) {
                    Transaction t = handleTransfer(state, step, parameters.transferLimit, clientTo);
                    transactions.add(t);
                    lastTransferFailed = !t.isSuccessful();
                    reducedAmount -= parameters.transferLimit;
                }
                if (reducedAmount > 0 && !lastTransferFailed) {
                    transactions.add(handleTransfer(state, step, reducedAmount, clientTo));
                }
                break;
            case DEPOSIT:
                transactions.add(handleDeposit(state, step, amount));
                break;
            default:
                throw new UnsupportedOperationException("Action not implemented in Client");
        }

        return transactions;
    }

    protected Transaction handleCashIn(PaySimState state, int step, double amount) {
        Merchant merchantTo = state.pickRandomMerchant();
        double oldBalanceOrig = this.getBalance();
        double oldBalanceDest = merchantTo.getBalance();

        this.deposit(amount);

        double newBalanceOrig = this.getBalance();
        double newBalanceDest = merchantTo.getBalance();

        merchantTo.rememberClient(this);
        return new Transaction(step, CASH_IN, amount, this, oldBalanceOrig,
                newBalanceOrig, merchantTo, oldBalanceDest, newBalanceDest);
    }

    protected Transaction handleCashOut(PaySimState state, int step, double amount) {
        Merchant merchantTo = state.pickRandomMerchant();
        double oldBalanceOrig = this.getBalance();
        double oldBalanceDest = merchantTo.getBalance();

        boolean isUnauthorizedOverdraft = this.withdraw(amount);

        double newBalanceOrig = this.getBalance();
        double newBalanceDest = merchantTo.getBalance();

        merchantTo.rememberClient(this);
        Transaction t = new Transaction(step, CASH_OUT, amount, this, oldBalanceOrig,
                newBalanceOrig, merchantTo, oldBalanceDest, newBalanceDest);

        t.setUnauthorizedOverdraft(isUnauthorizedOverdraft);
        t.setFraud(this.isFraud());
        return t;
    }

    protected Transaction handleDebit(PaySimState state, int step, double amount) {
        double oldBalanceOrig = this.getBalance();
        double oldBalanceDest = this.bank.getBalance();

        boolean isUnauthorizedOverdraft = this.withdraw(amount);

        double newBalanceOrig = this.getBalance();
        double newBalanceDest = this.bank.getBalance();

        this.bank.rememberClient(this);
        Transaction t = new Transaction(step, DEBIT, amount, this, oldBalanceOrig,
                newBalanceOrig, this.bank, oldBalanceDest, newBalanceDest);

        t.setUnauthorizedOverdraft(isUnauthorizedOverdraft);
        return t;
    }

    protected Transaction handlePayment(PaySimState state, int step, double amount) {
        Merchant merchantTo = state.pickRandomMerchant();

        double oldBalanceOrig = this.getBalance();
        double oldBalanceDest = merchantTo.getBalance();

        boolean isUnauthorizedOverdraft = this.withdraw(amount);
        if (!isUnauthorizedOverdraft) {
            merchantTo.deposit(amount);
        }

        double newBalanceOrig = this.getBalance();
        double newBalanceDest = merchantTo.getBalance();

        merchantTo.rememberClient(this);
        Transaction t = new Transaction(step, PAYMENT, amount, this, oldBalanceOrig,
                newBalanceOrig, merchantTo, oldBalanceDest, newBalanceDest);

        t.setUnauthorizedOverdraft(isUnauthorizedOverdraft);
        return t;
    }

    protected Transaction handleTransfer(PaySimState state, int step, double amount, Client clientTo) {
        double oldBalanceOrig = this.getBalance();
        double oldBalanceDest = clientTo.getBalance();

        clientTo.rememberClient(this);

        if (!isDetectedAsFraud(amount)) {
            boolean isUnauthorizedOverdraft = this.withdraw(amount);
            boolean transferSuccessful = !isUnauthorizedOverdraft;
            if (transferSuccessful) {
                clientTo.deposit(amount);
            }

            double newBalanceOrig = this.getBalance();
            double newBalanceDest = clientTo.getBalance();

            Transaction t = new Transaction(step, TRANSFER, amount, this, oldBalanceOrig,
                    newBalanceOrig, clientTo, oldBalanceDest, newBalanceDest);

            t.setUnauthorizedOverdraft(isUnauthorizedOverdraft);
            t.setFraud(this.isFraud());
            t.setSuccessful(transferSuccessful);
            return t;

        } else { // create the transaction but don't move any money as the transaction was detected as fraudulent
            double newBalanceOrig = this.getBalance();
            double newBalanceDest = clientTo.getBalance();

            Transaction t = new Transaction(step, TRANSFER, amount, this, oldBalanceOrig,
                    newBalanceOrig, clientTo, oldBalanceDest, newBalanceDest);

            t.setFlaggedFraud(true);
            t.setFraud(this.isFraud());
            t.setSuccessful(false);
            return t;
        }
    }

    protected Transaction handleDeposit(PaySimState state, int step, double amount) {
        double oldBalanceOrig = this.getBalance();
        double oldBalanceDest = this.bank.getBalance();

        this.deposit(amount);

        double newBalanceOrig = this.getBalance();
        double newBalanceDest = this.bank.getBalance();

        this.bank.rememberClient(this);
        return new Transaction(step, DEPOSIT, amount, this, oldBalanceOrig,
                newBalanceOrig, this.bank, oldBalanceDest, newBalanceDest);
    }

    private boolean isDetectedAsFraud(double amount) {
        /* XXX: It's not clear what the thinking is behind this method...why is there some native "fraud detection?"
                Historically, it seems there was a method for checking if a balance is rapidly decreasing, so not
                sure what the point is other than to simulate a crude fraud detection native to the system?
        */
        boolean isFraudulentAccount = false;
        if (this.countTransferTransactions >= MIN_NB_TRANSFER_FOR_FRAUD) {
            if (this.balanceMax - this.balance - amount > parameters.transferLimit * 2.5) {
                isFraudulentAccount = true;
            }
        } else {
            this.countTransferTransactions++;
            this.balanceMax = max(this.balanceMax, this.balance);
        }
        return isFraudulentAccount;
    }

    private double pickOverdraftLimit(MersenneTwisterFast random){
        double stdTransaction = 0;

        for (String action: ActionTypes.getActions()){
            double actionProbability = clientProfile.getActionProbability().get(action);
            ClientActionProfile actionProfile = clientProfile.getProfilePerAction(action);
            expectedAvgTransaction += actionProfile.getAvgAmount() * actionProbability;
            stdTransaction += Math.pow(actionProfile.getStdAmount() * actionProbability, 2);
        }
        stdTransaction = Math.sqrt(stdTransaction);

        double randomizedMeanTransaction = random.nextGaussian() * stdTransaction + expectedAvgTransaction;

        return BalancesClients.getOverdraftLimit(randomizedMeanTransaction);
    }

    @Override
    public Type getType() {
        return Type.CLIENT;
    }
}
