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

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import sun.misc.IOUtils;
import tor.util.TrustAllManager;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.Security;
import java.util.TreeMap;

public class TorSocket {

    private static Consensus consensus;

    SSLSocket sslsocket;
    OutputStream out;
    InputStream in;
    public int PROTOCOL_VERSION = 3; // auto negotiated later - this is minimum value supported.
    protected int PROTOCOL_VERSION_MAX = 4; // max protocol version supported

    OnionRouter firstHop; // e.g. hop connected to

    public Class defaultTorCircuitClass = TorCircuit.class;

    // circuits for this socket
    TreeMap<Integer, TorCircuit> circuits = new TreeMap<>();

    //LinkedBlockingQueue<Cell> sendQueue = new LinkedBlockingQueue<Cell>();
    enum STATES {
        INITIALISING, READY
    }

    ;

    private STATES state = STATES.INITIALISING;
    private Object stateNotify = new Object();

    /**
     * Send a cell given payload
     *
     * @param circid  Circuit ID
     * @param cmd     Cell Command.  See Cell.*
     * @param payload Cell Payload
     */
    public void sendCell(int circid, int cmd, byte[] payload)
            throws IOException {
        out.write(new Cell(circid, cmd, payload).getBytes(PROTOCOL_VERSION));
    }

    public void sendCell(Cell c)
            throws IOException {

        out.write(c.getBytes(PROTOCOL_VERSION));
    }

    private byte[] blockingRead(int length) throws IOException {
        return IOUtils.readFully(in, length, true);
    }

    public Cell recvCell() throws IOException {
        byte hdr[] = blockingRead(PROTOCOL_VERSION == 3 ? 3: 5);

        ByteBuffer buf = ByteBuffer.wrap(hdr);
        buf.order(ByteOrder.BIG_ENDIAN);

        int circid = 0;
        if(PROTOCOL_VERSION < 4)
            circid = buf.getShort();
        else
            circid = buf.getInt();

        int cmdId = buf.get() & 0xff;
        int pllength = 509;

        if (cmdId == 7 || cmdId >= 128) {
            pllength = ByteBuffer.wrap(blockingRead(2)).getShort();
        }

        byte payload[] = blockingRead(pllength);

        return new Cell(circid, cmdId, payload);

    }

    /**
     * Sends a NETINFO cell (used in connection init)
     */
    public void sendNetInfo() throws IOException {
        byte nibuf[] = new byte[4 + 2 + 4 + 3 + 4];
        byte[] remote = sslsocket.getInetAddress().getAddress();
        byte[] local = sslsocket.getLocalAddress().getAddress();
        int epoch = (int) (System.currentTimeMillis() / 1000L);
        ByteBuffer buf = ByteBuffer.wrap(nibuf);
        buf.putInt(epoch);
        buf.put(new byte[]{04, 04});   // remote's address
        buf.put(remote);
        buf.put(new byte[]{01, 04, 04});  // our address
        buf.put(local);
        sendCell(0, Cell.NETINFO, nibuf);
    }

    public void setState(STATES newState) {
        synchronized (stateNotify) {
            this.state = newState;
            this.stateNotify.notify();
        }
    }

    /**
     * Main loop.  Handles incoming cells and sends any data waiting to be send down circuits/streams
     */
    public void receiveHandlerLoop() {
        while (true) {
            // receive a cell
            Cell c = null;
            try {
                c = recvCell();

                switch (c.cmdId) {
                    case Cell.NETINFO:
                        sendNetInfo();
                        setState(STATES.READY);
                        continue;
                }
                TorCircuit circ = circuits.get(new Integer(c.circId));
                if (circ == null || !circ.handleCell(c))
                    System.out.println("unhandled cell " + c);

            } catch (IOException e) {
                e.printStackTrace();
                return;
            }
        }
    }

    /**
     * Creates a circuit
     *
     * @return TorCircuit object
     */
    public TorCircuit createCircuit(boolean blocking) {
        return createCircuit(defaultTorCircuitClass, blocking);
    }

    /**
     * Creates a circuit using a custom TorCircuit class
     *
     * @return TorCircuit object
     */
    public <T extends TorCircuit> T createCircuit(Class<T> torCircClass, boolean blocking) {
        T circ;
        try {
            circ = torCircClass.getDeclaredConstructor(TorSocket.class).newInstance(this);
        } catch (InstantiationException  | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
        circ.setBlocking(blocking);
        circuits.put(circ.circId, circ);
        return circ;
    }

    public TorSocket() {

    }

    /**
     * Main constructor. Connects and does connection setup.
     *
     * @param fh OnionRouter for first hop (used for Hostname/IP string and Port)
     */
    public TorSocket(OnionRouter fh) throws IOException {

        if (consensus == null) consensus = Consensus.getConsensus();

        firstHop = fh;
        if (firstHop == null)
            throw new RuntimeException("Couldn't find router ip in consensus");

        Security.addProvider(new BouncyCastleProvider());
        SSLContext sc;

        try {
            sc = SSLContext.getInstance("SSL");
            sc.init(null, new TrustManager[] { new TrustAllManager() }, new java.security.SecureRandom());
        } catch (KeyManagementException | NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }

        // connect
        sslsocket = (SSLSocket) sc.getSocketFactory().createSocket(firstHop.ip, firstHop.orport);

        out = sslsocket.getOutputStream();
        in = sslsocket.getInputStream();


        // versions cell
        sendCell(0, Cell.VERSIONS, new byte[]{00, 03, 00, 04});
        Cell versionReply = recvCell();
        ByteBuffer verBuf = ByteBuffer.wrap(versionReply.payload);
        for (int i = 0; i < versionReply.payload.length; i+=2) {
            int offeredVer = verBuf.getShort();
            if(offeredVer <= PROTOCOL_VERSION_MAX && offeredVer > PROTOCOL_VERSION)
                PROTOCOL_VERSION = offeredVer;
        }
        System.out.println("Negotiated protocol vesrsion: "+PROTOCOL_VERSION);

        new Thread(new Runnable() {
            @Override
            public void run() {
                receiveHandlerLoop();
            }
        }).start();

        while (state != STATES.READY) {
            synchronized (stateNotify) {
                try {
                    stateNotify.wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
