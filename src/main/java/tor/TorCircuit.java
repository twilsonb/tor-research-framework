/*
        Tor Research Framework - easy to use tor client library/framework
        Copyright (C) 2014  Dr Gareth Owen <drgowen@gmail.com>
        www.ghowen.me / github.com/drgowen/tor-research-framework

        This program is free software: you can redistribute it and/or modify
        it under the terms of the GNU General Public License as published by
        the Free Software Foundation, either version 3 of the License, or
        (at your option) any later version.

        This program is distributed in the hope that it will be useful,
        but WITHOUT ANY WARRANTY; without even the implied warranty of
        MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
        GNU General Public License for more details.

        You should have received a copy of the GNU General Public License
        along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package tor;

import org.apache.commons.lang.ArrayUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.util.encoders.Hex;
import tor.util.TorCircuitException;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.TreeMap;

public class TorCircuit {

    public static final int RELAY_BEGIN = 1;
    public static final int RELAY_DATA = 2;
    public static final int RELAY_END = 3;
    public static final int RELAY_CONNECTED = 4;
    public static final int RELAY_SENDME = 5;
    public static final int RELAY_EXTEND = 6;
    public static final int RELAY_EXTENDED = 7;
    public static final int RELAY_TRUNCATE = 8;
    public static final int RELAY_TRUNCATED = 9;
    public static final int RELAY_DROP = 10;
    public static final int RELAY_RESOLVE = 11;
    public static final int RELAY_RESOLVED = 12;
    public static final int RELAY_BEGIN_DIR = 13;
    public static final int RELAY_COMMAND_ESTABLISH_INTRO = 32;
    public static final int RELAY_COMMAND_ESTABLISH_RENDEZVOUS = 33;
    public static final int RELAY_COMMAND_INTRODUCE1 = 34;
    public static final int RELAY_COMMAND_INTRODUCE2 = 35;
    public static final int RELAY_COMMAND_RENDEZVOUS1 = 36;
    public static final int RELAY_COMMAND_RENDEZVOUS2 = 37;
    public static final int RELAY_COMMAND_INTRO_ESTABLISHED = 38;
    public static final int RELAY_COMMAND_RENDEZVOUS_ESTABLISHED = 39;
    public static final int RELAY_COMMAND_INTRODUCE_ACK = 40;
    final static Logger log = LogManager.getLogger();
    public static short streamId_counter = 1;
    public static String[] DESTROY_ERRORS = {"NONE", "PROTOCOL", "INTERNAL", "REQUESTED", "HIBERNATING",
            "RESOURCELIMIT", "CONNECTFAILED", "OR_IDENTITY", "OR_CONN_CLOSED",
            "FINISHED", "TIMEOUT", "DESTROYED", "NOSUCHSERVICE"};
    public static String[] STREAM_ERRORS = {"-", "REASON_MISC", "REASON_RESOLVEFAILED", "REASON_CONNECTREFUSED",
            "REASON_EXITPOLICY", "REASON_DESTROY", "REASON_DONE", "REASON_TIMEOUT",
            "REASON_NOROUTE", "REASON_HIBERNATING", "REASON_INTERNAL",
            "REASON_RESOURCELIMIT", "REASON_CONNRESET", "REASON_TORPROTOCOL",
            "REASON_NOTDIRECTORY"};
    private static int circId_counter = 1;
    // temp vars for created/extended
    public BigInteger temp_x;
    public OnionRouter temp_r;
    public STATES state = STATES.NONE;
    public byte[] rendezvousCookie = new byte[20];
    /**
     * Handles cell for this circuit
     *
     * @param c Cell to handle
     * @return Successfully handled
     */
    public long receiveWindow = 1000;
    public long sendWindow = 1000;
    long circId = 0;
    boolean blocking = false;
    // list of active streams for this circuit
    TreeMap<Integer, TorStream> streams = new TreeMap<>();
    // streams with packets to send
    /**
     *
     */
    //UniqueQueue <TorStream> streamsSending = new UniqueQueue<TorStream>();

    TorSocket sock;
    /**
     * Gererates a relay cell, encrypts and sends it
     *
     * @param payload Relay payload
     * @param relaytype Type of relay cell (see RELAY_)
     * @param early Whether to use an early cell (needed for EXTEND only)
     * @param stream Stream ID
     */
    long sentPackets = 0;
    long sentBytes = 0;
    // this circuit hop
    private LinkedList<OnionRouter> circuitToBuild = new LinkedList<>();
    private ArrayList<TorHop> hops = new ArrayList<>();
    private Object stateNotify = new Object();

    public TorCircuit(TorSocket sock) {
        circId = circId_counter++;
        // in proto version 4 or higher, the MSB bit of the circId must be one for the initiator (aka, us).
        if (sock.PROTOCOL_VERSION >= 4)
            circId |= 0x80000000;
        this.sock = sock;
    }

    public TorCircuit(int cid, TorSocket sock) {
        circId = cid;
        this.sock = sock;
    }

    public void setBlocking(boolean blocking) {
        this.blocking = blocking;
    }

    public void setState(STATES newState) {
        log.trace("[Circ {}] New Circuit state {} (oldState {})", circId, newState, state);
        synchronized (stateNotify) {
            state = newState;
            stateNotify.notifyAll();
        }
    }

    public void waitForState(STATES desired, boolean waitIfAlready) throws IOException {
        if (!waitIfAlready && state.equals(desired))
            return;
        while (true) {
            synchronized (stateNotify) {
                try {
                    stateNotify.wait();
                    if (state == STATES.DESTROYED && desired != STATES.DESTROYED)
                        log.exit("Waiting for unreachable state - circuit destroyed");
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            if (state.equals(desired))
                return;
        }
    }

    /**
     * Utility function to create routes
     *
     * @param hopList Comma separated list on onion router names
     * @throws IOException
     */
    public void createRoute(String hopList) throws IOException {
        if (state == STATES.DESTROYED) {
            log.error("Trying to use destroyed circuit");
            throw new RuntimeException("Trying to use destroyed circuit");
        }

        String hops[] = hopList.split(",");
        for (String s : hops) {
            circuitToBuild.add(Consensus.getConsensus().getRouterByName(s));
        }
        create(sock.firstHop); // must go to first hop first

        if (blocking)
            waitForState(STATES.READY, false);
    }

    public void create() throws IOException {
        create(sock.firstHop);
    }

    /**
     * Sends a create cell to specified hop (usually first hop that we're already connected to)
     *
     * @param r Hop
     */
    public void create(OnionRouter r) throws IOException {
        if (state == STATES.DESTROYED) {
            log.error("Trying to use destroyed circuit");
            throw new RuntimeException("Trying to use destroyed circuit");
        }

        setState(STATES.CREATING);
        sock.sendCell(circId, Cell.CREATE, createPayload(r));

        if (blocking)
            waitForState(STATES.READY, true);
    }

    /**
     * Builds create cell payload (e.g. tap handshake)
     *
     * @param r Hop to create to
     * @return Payload
     * @throws IOException
     */
    private byte[] createPayload(OnionRouter r) throws IOException {
        byte privkey[] = new byte[40];

        // generate priv key
        TorCrypto.rnd.nextBytes(privkey);
        temp_x = TorCrypto.byteToBN(privkey);
        temp_r = r;

        // generate pub key
        BigInteger pubKey = TorCrypto.DH_G.modPow(temp_x, TorCrypto.DH_P);
        byte pubKeyByte[] = TorCrypto.BNtoByte(pubKey);
        return TorCrypto.hybridEncrypt(pubKeyByte, r.getOnionKey());
    }

    /**
     * Builds a relay cell payload (not including cell header, only relay header)
     *
     * @param toHop   Hop that it's destined for
     * @param cmd     Command ID, see RELAY_
     * @param stream  Stream ID
     * @param payload Relay cell data
     * @return Constructed relay payload
     */
    protected synchronized byte[] buildRelay(TorHop toHop, int cmd, short stream, byte[] payload) {
        byte[] fnl = new byte[509];
        ByteBuffer buf = ByteBuffer.wrap(fnl);
        buf.put((byte) cmd);
        buf.putShort((short) 0); // recognised
        buf.putShort(stream);
        buf.putInt(0); // digest

        if (payload != null) {
            buf.putShort((short) payload.length);
            buf.put(payload);
        } else {
            buf.putShort((short) 0);
        }

        toHop.df_md.update(fnl);
        MessageDigest md;
        try {
            md = (MessageDigest) toHop.df_md.clone();

            byte digest[] = md.digest();

            //byte [] fnl_final = new byte[509];
            System.arraycopy(digest, 0, fnl, 5, 4);

            return fnl;
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }

    }

    /**
     * Wraps data in onion skins for sending down circuit
     *
     * @param data Data to wrap/encrypt
     * @return Wrapped/encrypted data
     */
    private byte[] encrypt(byte[] data) {
        for (int i = hops.size() - 1; i >= 0; i--) {
            data = hops.get(i).encrypt(data);
        }
        return data;
    }

    /**
     * Removes onion skins for received data
     *
     * @param data Encrypted data for onion skin removal.
     * @return Decrypted data.
     */
    // TODO: should check digest in this function too - otherwise might miss packets with 1/65535 probability.
    private byte[] decrypt(byte[] data) {
        for (TorHop hop : hops) {
            data = hop.decrypt(data);
        }
        return data;
    }

    /**
     * Return the last TorHop in this circuit, excluding the first hop.
     *
     * @return the last TorHop, or null if there are no TorHops in the circuit.
     */
    public TorHop getLastHop() {
        if (hops.size() < 1)
            return null;
        else
            return hops.get(hops.size() - 1);
    }

    /**
     * Sends an extend cell to extend the circuit to specified hop
     *
     * @param nextHop Hop to extend to
     * @throws IOException
     */
    public void extend(OnionRouter nextHop) throws IOException {
        if (state == STATES.DESTROYED) {
            log.error("Trying to use destroyed circuit");
            throw new RuntimeException("Trying to use destroyed circuit");
        }

        // Unused, throws ArrayIndexOutOfBoundsException when extend() is called after createCircuit()
        // without the fix to getLastHop() which returns null when hops.size() == 0
        //TorHop lastHop = getLastHop();

        byte create[] = createPayload(nextHop);
        byte extend[] = new byte[4 + 2 + create.length + TorCrypto.HASH_LEN];
        ByteBuffer buf = ByteBuffer.wrap(extend);
        buf.put(nextHop.ip.getAddress());
        buf.putShort((short) nextHop.orport);
        buf.put(create);
        buf.put(Hex.decode(nextHop.identityhash));

        send(extend, RELAY_EXTEND, true, (short) 0);
        //byte []payload = encrypt(buildRelay(lastHop, RELAY_EXTEND, (short)0, extend));
        //sock.sendCell(circId, Cell.RELAY_EARLY, payload);

        setState(STATES.EXTENDING);

        if (blocking)
            waitForState(STATES.READY, false);
    }

    /**
     * Handles created cell (also used for extended cell as payload the same)
     *
     * @param in Cell payload (e.g. handshake data)
     */
    private void handleCreated(byte in[]) throws TorCircuitException {
        // other side's public key
        byte y_bytes[] = Arrays.copyOfRange(in, 0, TorCrypto.DH_LEN);

        // kh for verification of derivation
        byte kh[] = Arrays.copyOfRange(in, TorCrypto.DH_LEN, TorCrypto.DH_LEN + TorCrypto.HASH_LEN);

        //calculate g^xy shared secret
        BigInteger secret = TorCrypto.byteToBN(y_bytes).modPow(temp_x, TorCrypto.DH_P);

        // derive key data data
        byte kdf[] = TorCrypto.torKDF(TorCrypto.BNtoByte(secret), 3 * TorCrypto.HASH_LEN + 2 * TorCrypto.KEY_LEN);

        // ad hop
        hops.add(new TorHop(kdf, kh, temp_r));

        if (circuitToBuild.isEmpty())
            setState(STATES.READY);
    }

    public TorStream createDirStream(TorStream.TorStreamListener list) throws IOException {
        if (state == STATES.DESTROYED) {
            log.error("Trying to use destroyed circuit");
            throw new RuntimeException("Trying to use destroyed circuit");
        }

        //TODO: allocate stream and circuit IDS properly
        int stid = streamId_counter++;
        send(null, RELAY_BEGIN_DIR, false, (short) stid);
        TorStream st = new TorStream(stid, this, list);
        streams.put(stid, st);
        return st;
    }

    /**
     * Creates a stream using this circuit and connects to a host
     *
     * @param host Hostname/ip
     * @param port Port
     * @param list A listener for stream events
     * @return TorStream object
     */
    public TorStream createStream(String host, int port, TorStream.TorStreamListener list) throws IOException {
        if (state == STATES.DESTROYED) {
            log.error("Trying to use destroyed circuit");
            throw new RuntimeException("Trying to use destroyed circuit");
        }

        byte b[] = new byte[100];
        ByteBuffer buf = ByteBuffer.wrap(b);
        buf.put((host + ":" + port).getBytes("UTF-8"));
        buf.put((byte) 0); // null terminator
        buf.putInt(0);
        //TODO: allocate stream and circuit IDS properly
        int stid = streamId_counter++;
        send(b, RELAY_BEGIN, false, (short) stid);
        TorStream st = new TorStream(stid, this, list);
        streams.put(stid, st);
        return st;
    }

    // must be synchronised due to hash calculation - out of sync = bad
    public synchronized void send(byte[] payload, int relaytype, boolean early, short stream) throws IOException {
        if (state == STATES.DESTROYED) {
            log.error("Trying to use destroyed circuit");
            throw new RuntimeException("Trying to use destroyed circuit");
        }

        if (relaytype == RELAY_DATA)
            sendWindow--;

        byte relcell[] = buildRelay(hops.get(hops.size() - 1), relaytype, stream, payload);
        sock.sendCell(circId, early ? Cell.RELAY_EARLY : Cell.RELAY, encrypt(relcell));
        sentPackets++;
        sentBytes += relcell.length;
    }

    public void rendezvousSetup() throws IOException {
        TorCrypto.rnd.nextBytes(rendezvousCookie);
        rendezvousSetup(rendezvousCookie);
    }

    public void rendezvousSetup(byte[] cookie) throws IOException {
        rendezvousCookie = ArrayUtils.clone(cookie);

        send(rendezvousCookie, RELAY_COMMAND_ESTABLISH_RENDEZVOUS, false, (short) 0);
        setState(STATES.RENDEZVOUS_WAIT);

        if (blocking)
            waitForState(STATES.RENDEZVOUS_ESTABLISHED, false);
    }

    public void destroy() throws IOException {
        sock.sendCell(circId, Cell.DESTROY, null);
    }

    /**
     * Set the digest to zero for a relay payload - used by hash calculation code in handleReceived()
     *
     * @param relayCell
     * @return cell with digest removed
     */
    public byte[] relayCellRemoveDigest(byte[] relayCell) {
        byte buf[] = new byte[relayCell.length];
        System.arraycopy(relayCell, 0, buf, 0, 5);
        System.arraycopy(relayCell, 9, buf, 9, relayCell.length - 9);
        return buf;
    }

    public boolean handleCell(Cell c) throws IOException {
        boolean handled = false;

        if (receiveWindow < 900) {
            //System.out.println("sent SENDME (CIRCUIT): " + receiveWindow);
            send(null, RELAY_SENDME, false, (short) 0);
            receiveWindow += 100;
        }

        if (state == STATES.DESTROYED) {
            log.error("Trying to use destroyed circuit");
            throw new RuntimeException("Trying to use destroyed circuit");
        }

        if (c.cmdId == Cell.CREATED) // create
        {
            handleCreated(c.payload);

            if (!circuitToBuild.isEmpty()) {// more?
                boolean block = blocking;
                setBlocking(false);
                extend(circuitToBuild.removeFirst());
                setBlocking(block);
            }

            handled = true;
        } else if (c.cmdId == Cell.RELAY_EARLY || c.cmdId == Cell.PADDING || c.cmdId == Cell.VPADDING) { // these are used in deanon attacks
            log.error("WARNING**** cell CMD " + c.cmdId + " received in - Possible DEANON attack!!: Route: " + hops.toArray());
        } else if (c.cmdId == Cell.RELAY) // relay cell
        {
            // cell decrypt logic - decrypt from each hop in turn checking recognised and digest until success
            // remember, we can receive cells from intermediate hops, so it's an iterative decrypt and check if successful
            // for each hop.
            int cellFromHop = -1;
            for (int di = 0; di < hops.size(); di++) {  // loop through circuit hops
                TorHop hop = hops.get(di);
                c.payload = hop.decrypt(c.payload); // decrypt for this hop

                if (c.payload[1] == 0 && c.payload[2] == 0) { // are recognised bytes set to zero?
                    byte nodgrl[] = relayCellRemoveDigest(c.payload); // remove digest from cell
                    byte hash[] = Arrays.copyOfRange(c.payload, 5, 9); // put digest from cell into hash
                    byte[] digest;

                    try {  // calculate the digest that we thing it should be
                        // must clone here to stop clobbering original
                        digest = ((MessageDigest) hop.db_md.clone()).digest(nodgrl);
                    } catch (CloneNotSupportedException e) {
                        throw new RuntimeException(e);
                    }

                    // compare our calculations with digest in cell - if right, we've decrypted correctly
                    if (Arrays.equals(hash, Arrays.copyOfRange(digest, 0, 4))) {
                        hop.db_md.update(nodgrl); // update digest for hop for future cells
                        cellFromHop = di;  // hop number this cell is from
                        break;
                    }
                }
            }

            if (cellFromHop == -1) {
                log.warn("unrecognised cell - didn't decrypt");
                return false;
            }
            // successfully decrypted - now let's proceed

            // decode relay header
            ByteBuffer buf = ByteBuffer.wrap(c.payload);
            int cmd = buf.get();
            if (buf.getShort() != 0) {
                log.warn("invalid relay cell");
                return false;
            }
            int streamid = buf.getShort();

            int digest = buf.getInt();
            int length = buf.getShort();
            byte data[] = Arrays.copyOfRange(c.payload, 1 + 2 + 2 + 4 + 2, 1 + 2 + 2 + 4 + 2 + length);

            // now pass cell off to handler function below
            handled = handleRelayCell(cmd, streamid, cellFromHop, data);

        } else if (c.cmdId == Cell.DESTROY) {
            log.info("Circuit destroyed " + circId);
            log.info("Reason: " + DESTROY_ERRORS[c.payload[0]]);
            for (TorStream s : streams.values()) {
                s.notifyDisconnect();
            }
            setState(STATES.DESTROYED);
            handled = true;
        }

        return handled;
    }

    /**
     * Handles a decrypted relay cell
     *
     * @param cmdId    RELAY_* command ID
     * @param streamId Stream ID
     * @param fromHop  Received from which hop in circuit, e.g., 0,1,2..
     * @param payload  Decrypted payload
     * @return whether handled or not
     * @throws IOException
     */
    public boolean handleRelayCell(int cmdId, int streamId, int fromHop, byte[] payload) throws IOException {
        TorStream stream = streams.get(new Integer(streamId));

        log.trace("Got RELAY cell with streamId{} cmdID {}", streamId, cmdId);

        if (streamId > 0 && stream == null) {
            log.info("invalid stream id " + streamId);
            return false;
        }

        if (fromHop != hops.size() - 1)
            log.info("Cell from intermediate hop " + fromHop);

        switch (cmdId) {
            case RELAY_DROP:
                log.warn("WARNING**** _relay_ cell CMD DROP received - Possible DEANON attack!!: Route: " + hops.toArray());
                break;

            case RELAY_COMMAND_INTRODUCE_ACK:
                setState(STATES.INTRODUCED);
                break;

            case RELAY_COMMAND_RENDEZVOUS2:
                assert state == STATES.RENDEZVOUS_ESTABLISHED;
                handleCreated(payload);
                setState(STATES.RENDEZVOUS_COMPLETE);
                break;

            case RELAY_COMMAND_RENDEZVOUS_ESTABLISHED:
                setState(STATES.RENDEZVOUS_ESTABLISHED);
                break;

            case RELAY_TRUNCATED:
                log.error("TRUNCATED CELL RECEIVED - Cannot handle yet! " + DESTROY_ERRORS[payload[0]]);
                for (int hi = hops.size() - 1; hi > fromHop; hi--) {
                    log.info("removing hop " + hi + " from circ");
                    hops.remove(hi);
                }

                throw new RuntimeException("see err above");
                //break;

            case RELAY_EXTENDED: // extended
                handleCreated(payload);

                if (!circuitToBuild.isEmpty()) { // needs extending further?
                    boolean block = blocking;
                    setBlocking(false);
                    extend(circuitToBuild.removeFirst());
                    setBlocking(block);
                } else {
                    log.info("Circuit build complete");
                    setState(STATES.READY);
                }
                break;
            case RELAY_CONNECTED:
                if (stream != null)
                    stream.notifyConnect();
                break;
            case RELAY_SENDME:
                if (streamId == 0)
                    sendWindow += 100;
                log.trace("RELAY_SENDME circ " + circId + " Stream " + streamId + " cur window " + sendWindow);
                break;
            case RELAY_DATA:
                if (state == STATES.READY)
                    receiveWindow--;
                if (stream != null)
                    stream._putRecved(payload);
                break;
            case RELAY_END:
                if (payload[0] != 6)
                    log.info("Remote stream closed with error code " + STREAM_ERRORS[payload[0]]);
                if (stream != null) {
                    stream.notifyDisconnect();
                    streams.remove(new Integer(streamId));
                }
                break;
            default:
                log.warn("unknown relay cell cmd " + cmdId);
                return false;
        }
        return true;

    }

    public enum STATES {NONE, CREATING, EXTENDING, READY, DESTROYED, RENDEZVOUS_WAIT, RENDEZVOUS_ESTABLISHED, RENDEZVOUS_COMPLETE, INTRODUCED}

}
