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
package tor.examples;

import tor.Consensus;
import tor.TorCircuit;
import tor.TorSocket;
import tor.TorStream;

import java.io.IOException;

/**
 * Created by teb on 29/07/2014.
 * Based on SimpleExample created by gho on 26/07/14.
 */
public class RandomRouteExample {
    public static void main(String[] args) throws IOException {
        Consensus con = Consensus.getConsensus();
        // If you're having speed issues, try adding "Fast" to the lists of flags below.
        TorSocket sock = new TorSocket(con.getRandomORWithFlag("Guard,Running,Valid"));
        //TorSocket sock = new TorSocket(con.getRouterByName("turtles"));
        TorCircuit circ = sock.createCircuit(true);

        circ.create();
        circ.extend(con.getRandomORWithFlag("Running,Valid"));
        circ.extend(con.getRandomORWithFlag("Exit,Running,Valid".split(","), 80));
        //circ.createRoute("Snowden4ever,abbie");

        TorStream stream = circ.createStream("www.amazon.com", 80, new TorStream.TorStreamListener() {
            @Override
            public void dataArrived(TorStream s) {
                try {
                    // The ">>>" makes it hard to view the downloaded HTML in a browser
                    System.out.println(/* ">>>" +*/ new String(s.recv(1024,false)));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void connected(TorStream s) {
                try {
                    s.sendHTTPGETRequest("/", "www.amazon.com");
                } catch (IOException e) {
                    e.printStackTrace();
                }

            }

            @Override public void disconnected(TorStream s) {  }
            @Override public void failure(TorStream s) {  }
        });
    }
}
