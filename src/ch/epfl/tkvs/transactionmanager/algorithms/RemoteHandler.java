package ch.epfl.tkvs.transactionmanager.algorithms;

import static ch.epfl.tkvs.transactionmanager.communication.utils.JSON2MessageConverter.parseJSON;

import java.io.IOException;

import org.codehaus.jettison.json.JSONObject;

import ch.epfl.tkvs.exceptions.AbortException;
import ch.epfl.tkvs.exceptions.PrepareException;
import ch.epfl.tkvs.exceptions.RemoteTMException;
import ch.epfl.tkvs.transactionmanager.Transaction;
import ch.epfl.tkvs.transactionmanager.TransactionManager;
import ch.epfl.tkvs.transactionmanager.communication.Message;
import ch.epfl.tkvs.transactionmanager.communication.requests.AbortRequest;
import ch.epfl.tkvs.transactionmanager.communication.requests.BeginRequest;
import ch.epfl.tkvs.transactionmanager.communication.requests.CommitRequest;
import ch.epfl.tkvs.transactionmanager.communication.requests.PrepareRequest;
import ch.epfl.tkvs.transactionmanager.communication.requests.ReadRequest;
import ch.epfl.tkvs.transactionmanager.communication.requests.WriteRequest;
import ch.epfl.tkvs.transactionmanager.communication.responses.GenericSuccessResponse;
import ch.epfl.tkvs.transactionmanager.communication.responses.ReadResponse;
import ch.epfl.tkvs.transactionmanager.communication.utils.JSON2MessageConverter.InvalidMessageException;
import ch.epfl.tkvs.yarn.HDFSLogger;


/**
 * Handles cooperation between {@link TransactionManager}. It is responsible for 2 Phase Commit.
 */
public class RemoteHandler {

    // The concurrency control algorithm being executed locally to which the remote handler is attached to.
    private CCAlgorithm localAlgo;
    private HDFSLogger log;

    // public RemoteHandler(CCAlgorithm localAlgo)
    public void setAlgo(CCAlgorithm localAlgo, HDFSLogger log) {
        this.localAlgo = localAlgo;
        this.log = log;
    }

    private Message sendToRemoteTM(Message request, int localityHash, Class<? extends Message> messageClass) throws IOException, InvalidMessageException {

        log.info(request, RemoteHandler.class);
        JSONObject response = TransactionManager.sendToTransactionManager(localityHash, request, true);
        Message responseMessage = parseJSON(response, messageClass);
        log.info(responseMessage + "<--" + request, RemoteHandler.class);
        return responseMessage;

    }

    private void sendToRemoteTM(Message request, int localityHash) throws IOException {

        TransactionManager.sendToTransactionManager(localityHash, request, false);
    }

    /**
     * Initiates a transaction on secondary {@link TransactionManager} for distributed transaction
     *
     * @param t The transaction running on primary {@link TransactionManager}
     * @param hash The hash code of key for identifying the remote Transaction Manager
     * @throws ch.epfl.tkvs.exceptions.AbortException in case the operation was not successful
     * 
     */
    public void begin(Transaction t, int hash) throws AbortException {
        hash = hash % TransactionManager.getNumberOfTMs();
        if (!t.remoteIsPrepared.containsKey(hash)) {
            try {
                GenericSuccessResponse response = (GenericSuccessResponse) sendToRemoteTM(new BeginRequest(t.transactionId, false), hash, GenericSuccessResponse.class);
                if (!response.getSuccess()) {

                    throw new RemoteTMException(response.getExceptionMessage());
                }
                t.remoteIsPrepared.put(hash, Boolean.FALSE);
            } catch (IOException | InvalidMessageException ex) {
                log.error("Remote error", ex, RemoteHandler.class);
                throw new RemoteTMException(ex);
            }
        }

    }

    /**
     * Performs a remote read on secondary {@link TransactionManager} for distributed transaction Invokes distributed
     * abort in case of error
     *
     * @param t The transaction running on primary {@link TransactionManager}
     * @param request the original request received by primary {@link TransactionManager}
     * @return the response from the secondary {@link TransactionManager}
     */
    public ReadResponse read(Transaction t, ReadRequest request) {
        int tmHash = request.getLocalityHash();
        try {
            begin(t, tmHash);
            ReadResponse rr = (ReadResponse) sendToRemoteTM(request, tmHash, ReadResponse.class);
            if (!rr.getSuccess()) {
                throw new RemoteTMException(rr.getExceptionMessage());
            }
            return rr;
        } catch (IOException | InvalidMessageException ex) {
            log.fatal("Remote error", ex, RemoteHandler.class);
            abortAll(t);
            return new ReadResponse(new RemoteTMException(ex));
        } catch (AbortException e) {
            abortAll(t);
            return new ReadResponse(e);
        }

    }

    /**
     * Performs a remote write on secondary {@link TransactionManager} for distributed transaction Invokes distributed
     * abort in case of error
     *
     * @param t The transaction running on primary {@link TransactionManager}
     * @param request the original request received by primary {@link TransactionManager}
     * @return the response from the secondary {@link TransactionManager}
     */
    public GenericSuccessResponse write(Transaction t, WriteRequest request) {
        int tmHash = request.getLocalityHash();
        try {
            begin(t, tmHash);

            GenericSuccessResponse gsr = (GenericSuccessResponse) sendToRemoteTM(request, tmHash, GenericSuccessResponse.class);
            if (!gsr.getSuccess()) {
                throw new RemoteTMException(gsr.getExceptionMessage());
            }
            return gsr;
        } catch (IOException | InvalidMessageException ex) {
            log.fatal("Remote error", ex, RemoteHandler.class);
            abortAll(t);
            return new GenericSuccessResponse(new RemoteTMException(ex));
        } catch (AbortException ex) {
            abortAll(t);
            return new GenericSuccessResponse(ex);
        }
    }

    /**
     * Performs 2-Phase commit protocol to try to commit a distributed transaction. Invokes distributed abort in case of
     * error.
     *
     * @param t The transaction running on primary Transaction Manager
     * @return the response from the secondary Transaction Manager
     */
    public GenericSuccessResponse tryCommit(Transaction t) {
        PrepareRequest pr = new PrepareRequest(t.transactionId);
        GenericSuccessResponse response = localAlgo.prepare(pr);

        try {
            if (!response.getSuccess())
                throw new PrepareException(response.getExceptionMessage());
            for (Integer remoteHash : t.remoteIsPrepared.keySet()) {
                response = (GenericSuccessResponse) sendToRemoteTM(pr, remoteHash, GenericSuccessResponse.class);
                if (!response.getSuccess()) {
                    throw new PrepareException(response.getExceptionMessage());
                }

            }

            CommitRequest cr = new CommitRequest(t.transactionId);
            localAlgo.commit(cr);
            commitOthers(t);

            return new GenericSuccessResponse();

        } catch (IOException | InvalidMessageException ex) {
            abortAll(t);
            log.fatal("remote error", ex, RemoteHandler.class);
            return new GenericSuccessResponse(new RemoteTMException(ex));
        } catch (AbortException ex) {
            abortAll(t);
            return new GenericSuccessResponse(ex);
        }

    }

    /**
     * Sends commit message to secondary {@link TransactionManager}s. TODO: ensure that all commits are successful
     * @param t Transaction to be committed
     */
    private void commitOthers(Transaction t) throws IOException {
        CommitRequest cr = new CommitRequest(t.transactionId);
        for (Integer remoteHash : t.remoteIsPrepared.keySet()) {
            sendToRemoteTM(cr, remoteHash);
            // TODO: check response and do something?
        }
    }

    // Sends abort message to local algorithm which in turn would invoke abortOthers()
    private void abortAll(Transaction t) {
        AbortRequest ar = new AbortRequest(t.transactionId);
        localAlgo.abort(ar);
    }

    // TODO: return true or false?
    /**
     * Sends abort message to secondary {@link TransactionManager}s
     *
     * @param t Transaction to be committed
     * 
     */
    public void abortOthers(Transaction t) {
        if (t.areAllRemoteAborted) {
            return;
        }
        AbortRequest ar = new AbortRequest(t.transactionId);

        for (Integer remoteHash : t.remoteIsPrepared.keySet()) {
            try {
                sendToRemoteTM(ar, remoteHash);
                // TODO: check response and do something?
            } catch (IOException ex) {
                log.error(ex, RemoteHandler.class);
            }
        }
        t.areAllRemoteAborted = true;

    }
}
