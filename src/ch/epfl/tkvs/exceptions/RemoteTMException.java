package ch.epfl.tkvs.exceptions;

public class RemoteTMException extends AbortException {

    public RemoteTMException(String string) {
        super("Remote TM caused abort : " + string);
    }

    public RemoteTMException(Exception ex) {
        super("Invalid or no response from Remote TM. Exception :" + ex.getMessage() + "Aborting..");
    }
}