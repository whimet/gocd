/*************************GO-LICENSE-START*********************************
 * Copyright 2014 ThoughtWorks, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *************************GO-LICENSE-END***********************************/

package com.thoughtworks.go.server.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPrivateKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Date;
import java.util.GregorianCalendar;
import javax.security.auth.x500.X500Principal;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.x509.X509V1CertificateGenerator;
import org.mortbay.jetty.Request;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.SessionManager;
import org.mortbay.jetty.nio.SelectChannelConnector;
import org.mortbay.jetty.security.SslSocketConnector;
import org.mortbay.jetty.servlet.Context;
import org.mortbay.jetty.servlet.HashSessionManager;
import org.mortbay.jetty.servlet.SessionHandler;

import static com.thoughtworks.go.util.TestFileUtil.createTempFile;

/**
 * @understands test http server that is used to test http client code end-to-end
 *
 * Flicked from https://github.com/test-load-balancer/tlb (pre http-components) e19d4911b089eeaf1a2c
 */
public class HttpTestUtil {

    private static final String STORE_PASSWORD = "tlb";

    private Server server;
    private Thread blocker;
    private File serverKeyStore;

    public static class EchoServlet extends HttpServlet {
        @Override protected void doGet(HttpServletRequest request, HttpServletResponse resp) throws ServletException, IOException {
            handleRequest((Request) request, resp);
        }

        @Override protected void doPost(HttpServletRequest request, HttpServletResponse resp) throws ServletException, IOException {
            handleRequest(request, resp);
        }

        @Override protected void doPut(HttpServletRequest request, HttpServletResponse resp) throws ServletException, IOException {
            handleRequest(request, resp);
        }

        private void handleRequest(HttpServletRequest request, HttpServletResponse resp) throws IOException {
            PrintWriter writer = resp.getWriter();

            Request req = (Request) request;

            writer.write(req.getScheme());
            writer.write("://");
            writer.write(req.getLocalName());
            writer.write(":");
            writer.write(String.valueOf(req.getLocalPort()));
            writer.write(req.getContextPath());
            writer.write(req.getPathInfo());
            String query = req.getQueryString();
            if (query != null) {
                writer.write("?" + query);
            }

            writer.close();
        }
    }

    public static interface ContextCustomizer {
        void customize(Context ctx) throws Exception;
    }

    public HttpTestUtil(final ContextCustomizer customizer) throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        serverKeyStore = createTempFile("server.jks");
        prepareCertStore(serverKeyStore);
        server = new Server();
        Context ctx = new Context();
        SessionManager sm = new HashSessionManager();
        SessionHandler sh = new SessionHandler(sm);
        ctx.setSessionHandler(sh);
        customizer.customize(ctx);
        ctx.setContextPath("/go");
        server.addHandler(ctx);
    }

    public void httpConnector(final int port) {
        SelectChannelConnector connector = connectorWithPort(port);
        server.addConnector(connector);
    }

    public void httpConnector(final int port, final String host) {
        SelectChannelConnector connector = connectorWithPort(port);
        connector.setHost(host);
        server.addConnector(connector);
    }

    private SelectChannelConnector connectorWithPort(int port) {
        SelectChannelConnector connector = new SelectChannelConnector();
        connector.setPort(port);
        return connector;
    }

    public void httpsConnector(final int port) {
        SslSocketConnector socketConnector = new SslSocketConnector();
        socketConnector.setPort(port);
        socketConnector.setMaxIdleTime(30000);
        socketConnector.setKeystore(serverKeyStore.getAbsolutePath());
        socketConnector.setPassword(STORE_PASSWORD);
        socketConnector.setKeyPassword(STORE_PASSWORD);
        socketConnector.setWantClientAuth(false);
        server.addConnector(socketConnector);
    }

    public synchronized void start() throws InterruptedException {
        if (blocker != null)
            throw new IllegalStateException("Aborting server start, it seems server is already running.");

        blocker = new Thread(new Runnable() {
            public void run() {
                try {
                    server.start();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                try {
                    Thread.sleep(Integer.MAX_VALUE);
                } catch (InterruptedException e) {
                    //ignore
                }
            }
        });
        blocker.start();
        while (!server.isStarted()) {
            Thread.sleep(50);
        }
    }

    public synchronized void stop() {
        if (blocker == null)
            throw new IllegalStateException("Aborting server stop, it seems there is no server running.");

        try {
            server.stop();
            blocker.interrupt();
            blocker.join();
            blocker = null;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void prepareCertStore(File serverKeyStore) {
        KeyPair keyPair = generateKeyPair();
        X509Certificate cert = generateCert(keyPair);
        FileOutputStream os = null;
        try {
            KeyStore store = KeyStore.getInstance("JKS");
            store.load(null, null);
            store.setKeyEntry("test", keyPair.getPrivate(), STORE_PASSWORD.toCharArray(), new Certificate[]{cert});
            os = new FileOutputStream(serverKeyStore);
            store.store(os, STORE_PASSWORD.toCharArray());
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (os != null) {
                IOUtils.closeQuietly(os);
            }
        }
    }

    private X509Certificate generateCert(final KeyPair keyPair) {
        Date startDate = day(-1);
        Date expiryDate = day(+1);
        BigInteger serialNumber = new BigInteger("1000200030004000");

        X509V1CertificateGenerator certGen = new X509V1CertificateGenerator();
        X500Principal dnName = new X500Principal("CN=Test CA Certificate");

        certGen.setSerialNumber(serialNumber);
        certGen.setIssuerDN(dnName);
        certGen.setNotBefore(startDate);
        certGen.setNotAfter(expiryDate);
        certGen.setSubjectDN(dnName);                       // note: same as issuer
        certGen.setPublicKey(keyPair.getPublic());
        certGen.setSignatureAlgorithm("SHA1WITHRSA");

        try {
            return certGen.generate(keyPair.getPrivate());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Date day(final int offset) {
        GregorianCalendar gregorianCalendar = new GregorianCalendar();
        gregorianCalendar.add(GregorianCalendar.DAY_OF_MONTH, offset);
        return gregorianCalendar.getTime();
    }

    private KeyPair generateKeyPair() {
        try {
            KeyPair seed = KeyPairGenerator.getInstance("RSA", "BC").generateKeyPair();
            RSAPrivateKey privateSeed = (RSAPrivateKey) seed.getPrivate();
            RSAPublicKey publicSeed = (RSAPublicKey) seed.getPublic();
            KeyFactory fact = KeyFactory.getInstance("RSA", "BC");
            RSAPrivateKeySpec privateKeySpec = new RSAPrivateKeySpec(privateSeed.getModulus(), privateSeed.getPrivateExponent());
            RSAPublicKeySpec publicKeySpec = new RSAPublicKeySpec(publicSeed.getModulus(), publicSeed.getPublicExponent());
            return new KeyPair(fact.generatePublic(publicKeySpec), fact.generatePrivate(privateKeySpec));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}