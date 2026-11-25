package cr.portarretratos;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Conexiones a internet que funcionan en Android 4.4:
 * - activa TLS 1.2 (en 4.4 existe pero viene apagado),
 * - además de los certificados del sistema, confía en raíces modernas incluidas
 *   en la app (assets/certs), porque la tablet tiene las de 2015.
 */
final class Red {

    private static SSLSocketFactory fabrica;

    static synchronized void preparar(Context ctx) {
        if (fabrica != null) return;
        try {
            X509TrustManager sistema = gestor(null);
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            String[] certs = ctx.getAssets().list("certs");
            int i = 0;
            if (certs != null) for (String n : certs) {
                InputStream in = ctx.getAssets().open("certs/" + n);
                try { ks.setCertificateEntry("raiz" + (i++), cf.generateCertificate(in)); } finally { in.close(); }
            }
            X509TrustManager incluidos = gestor(ks);
            SSLContext tls = SSLContext.getInstance("TLS");
            tls.init(null, new TrustManager[]{new Combinado(sistema, incluidos)}, null);
            fabrica = new ConTls12(tls.getSocketFactory());
        } catch (Exception e) {
            fabrica = (SSLSocketFactory) SSLSocketFactory.getDefault();
        }
    }

    private static X509TrustManager gestor(KeyStore ks) throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        for (TrustManager tm : tmf.getTrustManagers()) if (tm instanceof X509TrustManager) return (X509TrustManager) tm;
        throw new IllegalStateException("sin gestor de certificados");
    }

    static HttpURLConnection abrir(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        if (c instanceof HttpsURLConnection && fabrica != null) ((HttpsURLConnection) c).setSSLSocketFactory(fabrica);
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "Portarretratos/1.0 (Android)");
        return c;
    }

    /** Respuesta de texto (también cuando el servidor contesta con error, p. ej. 400). */
    static String pedir(String metodo, String url, String cuerpoFormulario) throws IOException {
        HttpURLConnection c = abrir(url);
        try {
            c.setRequestMethod(metodo);
            if (cuerpoFormulario != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                OutputStream out = c.getOutputStream();
                out.write(cuerpoFormulario.getBytes("UTF-8"));
                out.close();
            }
            int codigo = c.getResponseCode();
            InputStream in = codigo >= 400 ? c.getErrorStream() : c.getInputStream();
            return in == null ? "" : new String(leer(in), "UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /** Descarga a un archivo y devuelve el tipo que dijo el servidor (Content-Type). */
    static String descargar(String url, File destino) throws IOException {
        HttpURLConnection c = abrir(url);
        try {
            c.setRequestProperty("Accept", "image/jpeg,image/png,image/webp;q=0.8,*/*;q=0.5");
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            String tipo = c.getContentType();
            File tmp = new File(destino.getAbsolutePath() + ".tmp");
            InputStream in = c.getInputStream();
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally { out.close(); in.close(); }
            if (destino.exists()) destino.delete();
            if (!tmp.renameTo(destino)) throw new IOException("no se pudo guardar");
            return tipo == null ? "?" : tipo;
        } finally {
            c.disconnect();
        }
    }

    private static byte[] leer(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        } finally { in.close(); }
    }

    /** Acepta el certificado si lo valida el sistema o las raíces incluidas en la app. */
    private static final class Combinado implements X509TrustManager {
        private final X509TrustManager a, b;
        Combinado(X509TrustManager a, X509TrustManager b) { this.a = a; this.b = b; }

        public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException {
            a.checkClientTrusted(chain, auth);
        }

        public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
            try { a.checkServerTrusted(chain, auth); }
            catch (CertificateException e) { b.checkServerTrusted(chain, auth); }
        }

        public X509Certificate[] getAcceptedIssuers() {
            List<X509Certificate> todos = new ArrayList<X509Certificate>();
            for (X509Certificate c : a.getAcceptedIssuers()) todos.add(c);
            for (X509Certificate c : b.getAcceptedIssuers()) todos.add(c);
            return todos.toArray(new X509Certificate[todos.size()]);
        }
    }

    /** Enciende TLS 1.1/1.2 en cada conexión (Android 4.4 los trae apagados). */
    private static final class ConTls12 extends SSLSocketFactory {
        private final SSLSocketFactory base;
        ConTls12(SSLSocketFactory base) { this.base = base; }

        private Socket ajustar(Socket s) {
            if (s instanceof SSLSocket) {
                SSLSocket ssl = (SSLSocket) s;
                List<String> quiero = new ArrayList<String>();
                for (String p : ssl.getSupportedProtocols())
                    if (p.equals("TLSv1.2") || p.equals("TLSv1.1") || p.equals("TLSv1")) quiero.add(p);
                if (!quiero.isEmpty()) ssl.setEnabledProtocols(quiero.toArray(new String[quiero.size()]));
            }
            return s;
        }

        public String[] getDefaultCipherSuites() { return base.getDefaultCipherSuites(); }
        public String[] getSupportedCipherSuites() { return base.getSupportedCipherSuites(); }
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return ajustar(base.createSocket(s, host, port, autoClose));
        }
        public Socket createSocket(String host, int port) throws IOException { return ajustar(base.createSocket(host, port)); }
        public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException {
            return ajustar(base.createSocket(host, port, local, localPort));
        }
        public Socket createSocket(InetAddress host, int port) throws IOException { return ajustar(base.createSocket(host, port)); }
        public Socket createSocket(InetAddress address, int port, InetAddress local, int localPort) throws IOException {
            return ajustar(base.createSocket(address, port, local, localPort));
        }
    }
}
