package ch.epfl.tkvs.yarn.appmaster;

import static ch.epfl.tkvs.transactionmanager.communication.utils.JSON2MessageConverter.parseJSON;
import static ch.epfl.tkvs.transactionmanager.communication.utils.Message2JSONConverter.toJSON;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Map.Entry;

import org.apache.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import ch.epfl.tkvs.transactionmanager.communication.JSONCommunication;
import ch.epfl.tkvs.transactionmanager.communication.requests.TransactionManagerRequest;
import ch.epfl.tkvs.transactionmanager.communication.responses.TransactionManagerResponse;
import ch.epfl.tkvs.transactionmanager.communication.utils.JSON2MessageConverter.InvalidMessageException;
import ch.epfl.tkvs.yarn.RoutingTable;
import ch.epfl.tkvs.yarn.appmaster.centralized_decision.DeadlockCentralizedDecider;
import ch.epfl.tkvs.yarn.appmaster.centralized_decision.ICentralizedDecider;


public class AMWorker extends Thread {

    private RoutingTable routing;
    private JSONObject jsonRequest;
    private Socket sock;
    private ICentralizedDecider centralizedDecider;
    
    private static Logger log = Logger.getLogger(AMWorker.class.getName());
    

    public AMWorker(RoutingTable routing, JSONObject input, Socket sock, ICentralizedDecider decider) {
        this.routing = routing;
        this.jsonRequest = input;
        this.sock = sock;
        this.centralizedDecider = decider;
    }

    public void run() {
        try {
            // Create the response
            JSONObject response = null;

            String messageType = jsonRequest.getString(JSONCommunication.KEY_FOR_MESSAGE_TYPE);
            
            switch (messageType) {

            case TransactionManagerRequest.MESSAGE_TYPE:
                TransactionManagerRequest request = (TransactionManagerRequest) parseJSON(jsonRequest, TransactionManagerRequest.class);
                response = getResponseForRequest(request);
                break;
            default:
            	if (centralizedDecider != null && centralizedDecider.shouldHandleMessageType(messageType)) {
            		
            		centralizedDecider.handleMessage(jsonRequest);
            		
            		if (centralizedDecider.readyToDecide()) {
            			centralizedDecider.performDecision();
            		}
            	}
            }

            // Send the response if it exists
            if (response != null) {
            	log.info("Response" + response.toString());
                PrintWriter out = new PrintWriter(sock.getOutputStream(), true);
                out.println(response.toString());
            }
            sock.close(); // Closing this socket will also close the socket's
                          // InputStream and OutputStream.
        } catch (IOException | JSONException | InvalidMessageException e) {
            log.error("Err", e);
        }
    }

    private JSONObject getResponseForRequest(TransactionManagerRequest request) throws JSONException, IOException {
        // TODO: Compute the hash of the key.
        int hash = 0;

        // TODO: get by hash. Now it just get the 1st.
        Entry<String, Integer> tm = routing.getTMs().entrySet().iterator().next();

        // TODO: Create a unique transactionID
        int transactionID = 0;

        return toJSON(new TransactionManagerResponse(true, transactionID, tm.getKey(), tm.getValue()));
    }
}