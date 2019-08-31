package com.tip.flows.land;

import co.paralleluniverse.fibers.Suspendable;
import com.tip.contracts.LandContract;
import com.tip.states.Coordinates;
import com.tip.states.LandState;
import com.google.common.collect.ImmutableList;
import net.corda.core.flows.*;
import net.corda.core.identity.Party;
import net.corda.core.node.ServiceHub;
import net.corda.core.transactions.SignedTransaction;
import net.corda.core.transactions.TransactionBuilder;
import net.corda.core.utilities.ProgressTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.List;

@InitiatingFlow
@StartableByRPC
public class RegisterLandFlow extends FlowLogic<SignedTransaction> {
    private final static Logger logger = LoggerFactory.getLogger(RegisterLandFlow.class);

    final String name;
    final Party landAffairsParty, bnoParty, regulatorParty;
    final List<Coordinates> polygon;
    final Date dateRegistered;
    final String description;
    final double originalValue;
    private final ProgressTracker.Step SENDING_TRANSACTION = new ProgressTracker.Step("Sending transaction to counterParty");
    private final ProgressTracker.Step GENERATING_TRANSACTION = new ProgressTracker.Step("Generating transaction based on new IOU.");
    private final ProgressTracker.Step VERIFYING_TRANSACTION = new ProgressTracker.Step("Verifying contract constraints.");
    private final ProgressTracker.Step SIGNING_TRANSACTION = new ProgressTracker.Step("Signing transaction with our private key.");
    private final ProgressTracker.Step GATHERING_SIGS = new ProgressTracker.Step("Gathering the counterparty's signature.") {
        @Override
        public ProgressTracker childProgressTracker() {
            logger.info("\uD83C\uDF3A \uD83C\uDF3A ProgressTracker childProgressTracker ...");
            return CollectSignaturesFlow.Companion.tracker();
        }
    };
    private final ProgressTracker.Step FINALISING_TRANSACTION = new ProgressTracker.Step("Obtaining notary signature and recording transaction.") {
        @Override
        public ProgressTracker childProgressTracker() {
            return FinalityFlow.Companion.tracker();
        }
    };

    // The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
    // checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call()
    // function.
    private final ProgressTracker progressTracker = new ProgressTracker(
            GENERATING_TRANSACTION,
            VERIFYING_TRANSACTION,
            SIGNING_TRANSACTION,
            GATHERING_SIGS,
            FINALISING_TRANSACTION,
            SENDING_TRANSACTION
    );

    @Override
    public ProgressTracker getProgressTracker() {
        return progressTracker;
    }

    public RegisterLandFlow(String name, Party landAffairsParty, Party bnoParty, Party regulatorParty,
                            List<Coordinates> polygon, Date dateRegistered, String description,
                            double originalValue) {
        this.name = name;
        this.landAffairsParty = landAffairsParty;
        this.bnoParty = bnoParty;
        this.regulatorParty = regulatorParty;
        this.polygon = polygon;
        this.dateRegistered = dateRegistered;
        this.description = description;
        this.originalValue = originalValue;
    }

    @Override
    @Suspendable
    public SignedTransaction call() throws FlowException {
        // We retrieve the notary identity from the network map.
        final ServiceHub serviceHub = getServiceHub();
        logger.info(" \uD83E\uDD1F \uD83E\uDD1F  \uD83E\uDD1F \uD83E\uDD1F  ... RegisterLandFlow call started ...");
        Party notary = serviceHub.getNetworkMapCache().getNotaryIdentities().get(0);
        LandState landState = new LandState(name,landAffairsParty,bnoParty,regulatorParty,polygon,
                new Date(),description,originalValue);

        LandContract.Register command = new LandContract.Register();
        logger.info(" \uD83D\uDD06 \uD83D\uDD06 \uD83D\uDD06 Notary: " + notary.getName().toString()
                + "  \uD83C\uDF4A landAffairsParty: " + landAffairsParty.getName().toString() + " \uD83E\uDDE9 bnoParty: " + bnoParty.getName().toString()
                + "  \uD83C\uDF4A regulatorParty: "+ regulatorParty.getName().toString() +" \uD83C\uDF4E  name: "
                + landState.getName().concat("  \uD83D\uDC9A originalValue") + landState.getOriginalValue());

        progressTracker.setCurrentStep(GENERATING_TRANSACTION);
        TransactionBuilder txBuilder = new TransactionBuilder(notary)
                .addOutputState(landState, LandContract.ID)
                .addCommand(command, landAffairsParty.getOwningKey(), regulatorParty.getOwningKey(), bnoParty.getOwningKey());

        progressTracker.setCurrentStep(VERIFYING_TRANSACTION);
        txBuilder.verify(serviceHub);
        logger.info(" \uD83D\uDD06 \uD83D\uDD06 \uD83D\uDD06 Land Register TransactionBuilder verified");
        // Signing the transaction.
        progressTracker.setCurrentStep(SIGNING_TRANSACTION);
        SignedTransaction signedTx = serviceHub.signInitialTransaction(txBuilder);
        logger.info(" \uD83D\uDD06 \uD83D\uDD06 \uD83D\uDD06 Land Register Transaction signInitialTransaction executed ...");
        logger.info(" \uD83D\uDD06 \uD83D\uDD06 \uD83D\uDD06 Transaction signInitialTransaction: ".concat(signedTx.toString()));

        FlowSession investorFlowSession = initiateFlow(regulatorParty);
        FlowSession customerFlowSession = initiateFlow(landAffairsParty);

        progressTracker.setCurrentStep(GATHERING_SIGS);
        logger.info(" \uD83D\uDD06 \uD83D\uDD06 \uD83D\uDD06 \uD83D\uDD06 Collecting Signatures ....");
        SignedTransaction signedTransaction = subFlow(new CollectSignaturesFlow(signedTx, ImmutableList.of(investorFlowSession, customerFlowSession), GATHERING_SIGS.childProgressTracker()));
        logger.info("\uD83C\uDFBD \uD83C\uDFBD \uD83C\uDFBD \uD83C\uDFBD  Signatures collected OK!  \uD83D\uDE21 \uD83D\uDE21 will call FinalityFlow ... \uD83C\uDF3A \uD83C\uDF3A  \uD83C\uDF3A \uD83C\uDF3A : ".concat(signedTransaction.toString()));

        SignedTransaction mSignedTransactionDone = subFlow(new FinalityFlow(signedTransaction, ImmutableList.of(investorFlowSession, customerFlowSession), FINALISING_TRANSACTION.childProgressTracker()));
        logger.info(" \uD83D\uDD06 \uD83D\uDD06 \uD83D\uDD06 \uD83D\uDD06 FinalityFlow has been executed ... \uD83E\uDD66  are we good? \uD83E\uDD66 ❄️ ❄️ ❄️");
        logger.info(" \uD83D\uDD06 \uD83D\uDD06 \uD83D\uDD06 \uD83D\uDD06 returning mSignedTransactionDone:  ❄️ ❄️ : ".concat(mSignedTransactionDone.toString()));
        return mSignedTransactionDone;
    }
}
