package cr.portarretratos;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Página web dentro de la tablet para manejar el portarretratos desde el teléfono
 * (misma red WiFi): subir y borrar fotos, subir marcos y editar fechas.txt.
 * Protegida con el PIN que se escribe en fechas.txt (pin = 1234).
 */
final class Servidor {

    static final int PUERTO = 8080;
    private static final long MAX_SUBIDA = 40L * 1024 * 1024;
    private static final Object MINIATURAS = new Object(); // una miniatura a la vez: la tablet tiene poca memoria

    private final Context ctx;
    private final Carpetas carpetas;
    private final ExecutorService hilos = Executors.newFixedThreadPool(3);
    private volatile ServerSocket socket;
    private volatile boolean vivo;
    /** PIN actual (viene de fechas.txt). null = acceso desde el teléfono apagado. */
    volatile String pin;
    /** Conexión con Facebook (la pone MainActivity). */
    volatile Facebook facebook;
    /** Abre el instalador de Android con un APK (lo pone MainActivity). */
    interface Instalador { void instalar(File apk); }
    volatile Instalador instalador;
    static final String URL_ULTIMA =
            "https://github.com/grodrigonza-design/lector/releases/download/portarretratos/Portarretratos.apk";
    private volatile String estadoActualizacion = "";
    private int fallos;
    private long bloqueadoHasta;

    Servidor(Context ctx, Carpetas carpetas) {
        this.ctx = ctx.getApplicationContext();
        this.carpetas = carpetas;
    }

    void iniciar() {
        vivo = true;
        Thread t = new Thread(new Runnable() {
            public void run() {
                while (vivo) {
                    try {
                        ServerSocket ss = new ServerSocket();
                        ss.setReuseAddress(true);
                        ss.bind(new InetSocketAddress(PUERTO));
                        socket = ss;
                        while (vivo) {
                            final Socket s = ss.accept();
                            hilos.execute(new Runnable() { public void run() { atender(s); } });
                        }
                    } catch (Exception e) {
                        if (vivo) dormir(5000); // puerto ocupado o red caída: reintentar
                    }
                }
            }
        }, "servidor-web");
        t.setDaemon(true);
        t.start();
    }

    void detener() {
        vivo = false;
        try { if (socket != null) socket.close(); } catch (IOException ignored) { }
        hilos.shutdownNow();
    }

    /** Dirección para escribir en el navegador del teléfono, o null si no hay WiFi. */
    static String direccion() {
        try {
            String otra = null;
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> ds = ni.getInetAddresses();
                while (ds.hasMoreElements()) {
                    InetAddress a = ds.nextElement();
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    String url = "http://" + a.getHostAddress() + ":" + PUERTO;
                    if (ni.getName().startsWith("wlan")) return url;
                    otra = url;
                }
            }
            return otra;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ peticiones

    private static final class Peticion {
        String metodo, ruta;
        final Map<String, String> params = new HashMap<String, String>();
        final Map<String, String> cabeceras = new HashMap<String, String>();
        long largo;
        InputStream cuerpo;
    }

    private void atender(Socket s) {
        try {
            s.setSoTimeout(60000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = new BufferedOutputStream(s.getOutputStream());
            Peticion p = leer(in);
            if (p != null) {
                try {
                    rutas(p, out);
                } catch (Exception e) {
                    responder(out, 500, "text/plain; charset=utf-8", ("Error: " + e).getBytes("UTF-8"), null);
                }
            }
            out.flush();
        } catch (Exception ignored) {
        } finally {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    private void rutas(Peticion p, OutputStream out) throws Exception {
        String r = p.ruta;
        if (r.equals("/") || r.equals("/index.html")) {
            responder(out, 200, "text/html; charset=utf-8", leerAsset("telefono.html"), null);
            return;
        }
        if (r.equals("/api/entrar") && p.metodo.equals("POST")) {
            String dado = new String(leerCuerpo(p, 100), "UTF-8").trim();
            String actual = pin;
            if (actual == null) { json(out, 409, "{\"error\":\"sin-pin\"}", null); return; }
            synchronized (this) {
                if (System.currentTimeMillis() < bloqueadoHasta) { json(out, 429, "{\"error\":\"bloqueado\"}", null); return; }
                if (!dado.equals(actual)) {
                    if (++fallos >= 10) { fallos = 0; bloqueadoHasta = System.currentTimeMillis() + 5 * 60 * 1000L; }
                    dormir(1200);
                    json(out, 403, "{\"error\":\"pin\"}", null);
                    return;
                }
                fallos = 0;
            }
            json(out, 200, "{\"ok\":true}",
                    "Set-Cookie: pr=" + token(actual) + "; Max-Age=31536000; Path=/; HttpOnly\r\n");
            return;
        }
        if (r.equals("/api/crear-pin") && p.metodo.equals("POST")) { crearPin(p, out); return; }
        if (!r.startsWith("/api/")) { responder(out, 404, "text/plain", "No existe".getBytes("UTF-8"), null); return; }

        // Desde aquí, todo pide haber entrado con el PIN.
        String actual = pin;
        if (actual == null) { json(out, 409, "{\"error\":\"sin-pin\"}", null); return; }
        if (!token(actual).equals(cookie(p, "pr"))) { json(out, 401, "{\"error\":\"entrar\"}", null); return; }

        if (r.equals("/api/fotos")) { json(out, 200, listaFotos(), null); return; }
        if (r.equals("/api/marcos")) { json(out, 200, listaMarcos(), null); return; }
        if (r.equals("/api/mini")) { miniatura(p, out); return; }
        if (r.equals("/api/foto") && p.metodo.equals("POST")) { subirFoto(p, out); return; }
        if (r.equals("/api/marco") && p.metodo.equals("POST")) { subirMarco(p, out); return; }
        if (r.equals("/api/borrar") && p.metodo.equals("POST")) { borrar(p, out); return; }
        if (r.equals("/api/fechas") && p.metodo.equals("GET")) {
            File f = carpetas.fechasActual();
            byte[] b = f == null ? new byte[0] : unirLineas(MainActivity.leerLineas(f)).getBytes("UTF-8");
            responder(out, 200, "text/plain; charset=utf-8", b, null);
            return;
        }
        if (r.equals("/api/fechas") && p.metodo.equals("POST")) { guardarFechas(p, out); return; }
        if (r.startsWith("/api/facebook")) { rutasFacebook(p, out); return; }
        if (r.startsWith("/api/app")) { rutasApp(p, out); return; }
        json(out, 404, "{\"error\":\"no-existe\"}", null);
    }

    // ------------------------------------------------------------------ fotos

    private String listaFotos() {
        List<File> fotos = carpetas.fotos();
        // las más nuevas primero
        Collections.sort(fotos, new Comparator<File>() {
            public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); }
        });
        List<File> delTelefono = new ArrayList<File>();
        for (File p : carpetas.propias()) delTelefono.add(new File(p, "fotos"));
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < fotos.size(); i++) {
            File f = fotos.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"id\":").append(texto(f.getAbsolutePath()))
              .append(",\"nombre\":").append(texto(f.getName()))
              .append(",\"telefono\":").append(Carpetas.dentroDe(f, delTelefono))
              .append('}');
        }
        return sb.append(']').toString();
    }

    private String listaMarcos() {
        List<File> delTelefono = new ArrayList<File>();
        for (File p : carpetas.propias()) delTelefono.add(new File(p, "marcos"));
        StringBuilder sb = new StringBuilder("[");
        boolean primero = true;
        for (Map.Entry<String, File> e : carpetas.marcos().entrySet()) {
            if (!primero) sb.append(',');
            primero = false;
            String n = e.getKey();
            int punto = n.lastIndexOf('.');
            sb.append("{\"id\":").append(texto(e.getValue().getAbsolutePath()))
              .append(",\"nombre\":").append(texto(punto > 0 ? n.substring(0, punto) : n))
              .append(",\"telefono\":").append(Carpetas.dentroDe(e.getValue(), delTelefono))
              .append('}');
        }
        return sb.append(']').toString();
    }

    private void subirFoto(Peticion p, OutputStream out) throws Exception {
        byte[] datos = leerCuerpo(p, MAX_SUBIDA);
        String ext = extension(datos);
        if (ext == null) { json(out, 415, "{\"error\":\"no es una imagen JPG, PNG o WEBP\"}", null); return; }
        String nombre = limpiar(p.params.get("nombre"));
        int punto = nombre.lastIndexOf('.');
        if (punto > 0) nombre = nombre.substring(0, punto);
        if (nombre.isEmpty()) nombre = "foto";
        File destino = new File(carpetas.fotosTelefono(), System.currentTimeMillis() + "-" + nombre + ext);
        escribir(destino, datos);
        carpetas.cambio = true;
        json(out, 200, "{\"ok\":true}", null);
    }

    private void subirMarco(Peticion p, OutputStream out) throws Exception {
        String nombre = Agenda.normalizar(p.params.get("nombre") == null ? "" : p.params.get("nombre"))
                .replace(' ', '-').replaceAll("[^a-z0-9_\\-]", "");
        if (nombre.isEmpty()) { json(out, 400, "{\"error\":\"falta el nombre del marco\"}", null); return; }
        byte[] datos = leerCuerpo(p, MAX_SUBIDA);
        String ext = extension(datos);
        if (ext == null) { json(out, 415, "{\"error\":\"no es una imagen PNG o JPG\"}", null); return; }
        File dir = carpetas.marcosTelefono();
        File[] viejos = dir.listFiles();
        if (viejos != null) for (File v : viejos) {
            String n = v.getName();
            int punto = n.lastIndexOf('.');
            if ((punto > 0 ? n.substring(0, punto) : n).equals(nombre)) v.delete(); // reemplazar
        }
        escribir(new File(dir, nombre + ext), datos);
        carpetas.cambio = true;
        json(out, 200, "{\"ok\":true,\"nombre\":" + texto(nombre) + "}", null);
    }

    private void borrar(Peticion p, OutputStream out) throws Exception {
        String id = p.params.get("id");
        if (id == null) { json(out, 400, "{\"error\":\"falta id\"}", null); return; }
        File f = new File(id);
        List<File> permitidas = new ArrayList<File>(carpetas.carpetasFotos());
        for (File pr : carpetas.propias()) permitidas.add(new File(pr, "marcos"));
        if (!f.isFile() || !Carpetas.dentroDe(f, permitidas)) { json(out, 404, "{\"error\":\"no existe\"}", null); return; }
        // Las de Facebook se ocultan: si se borraran, se volverían a descargar.
        boolean deFacebook = f.getParentFile() != null && f.getParentFile().getName().equals("fotos-facebook");
        boolean borrada = !deFacebook && f.delete();
        if (!borrada) carpetas.ocultar(f); // fotos de la tarjeta: Android 4.4 no deja borrarlas
        carpetas.cambio = true;
        json(out, 200, "{\"ok\":true,\"oculta\":" + !borrada + "}", null);
    }

    private void miniatura(Peticion p, OutputStream out) throws Exception {
        String id = p.params.get("id");
        File f = id == null ? null : new File(id);
        List<File> permitidas = new ArrayList<File>(carpetas.carpetasFotos());
        permitidas.addAll(carpetas.carpetasMarcos());
        if (f == null || !f.isFile() || !Carpetas.dentroDe(f, permitidas)) {
            responder(out, 404, "text/plain", new byte[0], null);
            return;
        }
        File cache = new File(carpetas.miniaturas(), md5(f.getAbsolutePath() + "|" + f.lastModified()) + ".jpg");
        if (!cache.isFile()) {
            synchronized (MINIATURAS) {
                if (!cache.isFile()) {
                    boolean marco = !Carpetas.dentroDe(f, carpetas.carpetasFotos());
                    Bitmap b = MainActivity.decodificar(f, 240, 240, marco);
                    if (b == null) { responder(out, 415, "text/plain", new byte[0], null); return; }
                    float esc = 320f / Math.max(b.getWidth(), b.getHeight());
                    if (esc < 1f) {
                        Bitmap chica = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * esc),
                                Math.round(b.getHeight() * esc), true);
                        if (chica != b) b.recycle();
                        b = chica;
                    }
                    File tmp = new File(cache.getAbsolutePath() + ".tmp");
                    FileOutputStream fo = new FileOutputStream(tmp);
                    b.compress(Bitmap.CompressFormat.JPEG, 75, fo);
                    fo.close();
                    b.recycle();
                    tmp.renameTo(cache);
                }
            }
        }
        responder(out, 200, "image/jpeg", leerArchivo(cache), "Cache-Control: max-age=86400\r\n");
    }

    // ------------------------------------------------------------------ fechas

    private void guardarFechas(Peticion p, OutputStream out) throws Exception {
        byte[] datos = leerCuerpo(p, 512 * 1024);
        String texto = new String(datos, "UTF-8");
        List<String> lineas = new ArrayList<String>();
        Collections.addAll(lineas, texto.split("\r\n|\r|\n"));
        Agenda a = Agenda.leer(lineas);
        if (a.pin == null || a.pin.isEmpty()) {
            json(out, 400, "{\"error\":\"Falta la línea  pin = ...  (sin ella no podría volver a entrar desde el teléfono)\"}", null);
            return;
        }
        escribir(carpetas.fechasTelefono(), texto.getBytes("UTF-8"));
        carpetas.cambio = true;
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"avisos\":[");
        for (int i = 0; i < a.avisos.size(); i++) { if (i > 0) sb.append(','); sb.append(texto(a.avisos.get(i))); }
        String cookie = a.pin.equals(pin) ? null
                : "Set-Cookie: pr=" + token(a.pin) + "; Max-Age=31536000; Path=/; HttpOnly\r\n";
        pin = a.pin;
        json(out, 200, sb.append("]}").toString(), cookie);
    }

    private void rutasFacebook(Peticion p, OutputStream out) throws Exception {
        Facebook fb = facebook;
        if (fb == null) { json(out, 503, "{\"error\":\"no disponible\"}", null); return; }
        String r = p.ruta;
        if (r.equals("/api/facebook") && p.metodo.equals("GET")) { json(out, 200, fb.estadoJson(), null); return; }
        if (!p.metodo.equals("POST")) { json(out, 405, "{\"error\":\"método\"}", null); return; }
        if (r.equals("/api/facebook/config")) {
            Map<String, String> f = formulario(new String(leerCuerpo(p, 4096), "UTF-8"));
            String app = f.get("app_id"), ct = f.get("client_token");
            if (app != null && !app.trim().matches("\\d{5,25}")) { json(out, 400, "{\"error\":\"El App ID son solo números.\"}", null); return; }
            if (ct != null && !ct.trim().matches("[0-9a-fA-F]{16,64}")) { json(out, 400, "{\"error\":\"El Client Token no se ve bien (son letras y números, unos 32).\"}", null); return; }
            fb.configurar(app, ct, f.get("album"));
        } else if (r.equals("/api/facebook/conectar")) fb.conectarAhora();
        else if (r.equals("/api/facebook/revisar")) fb.revisarAhora();
        else if (r.equals("/api/facebook/desconectar")) fb.desconectar();
        else { json(out, 404, "{\"error\":\"no-existe\"}", null); return; }
        dormir(1500); // dar tiempo a que aparezca el código
        json(out, 200, fb.estadoJson(), null);
    }

    // ------------------------------------------------------------------ actualizar la app

    private void rutasApp(Peticion p, OutputStream out) throws Exception {
        if (p.ruta.equals("/api/app") && p.metodo.equals("GET")) {
            long instalada = 0;
            try { instalada = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).lastUpdateTime; }
            catch (Exception ignored) { }
            json(out, 200, "{\"instalada\":" + instalada + ",\"estado\":" + texto(estadoActualizacion) + "}", null);
            return;
        }
        if (!p.metodo.equals("POST")) { json(out, 405, "{\"error\":\"método\"}", null); return; }
        final File apk = new File(carpetas.propiaEscritura(), "actualizacion.apk");
        if (p.ruta.equals("/api/app/subir")) {
            byte[] datos = leerCuerpo(p, MAX_SUBIDA);
            if (datos.length < 4 || datos[0] != 'P' || datos[1] != 'K' || datos[2] != 3 || datos[3] != 4) {
                json(out, 415, "{\"error\":\"Ese archivo no es un APK.\"}", null);
                return;
            }
            escribir(apk, datos);
            abrirInstalador(apk);
            json(out, 200, "{\"ok\":true}", null);
            return;
        }
        if (p.ruta.equals("/api/app/internet")) {
            estadoActualizacion = "Descargando la última versión…";
            new Thread(new Runnable() {
                public void run() {
                    try {
                        Red.descargar(URL_ULTIMA, apk);
                        abrirInstalador(apk);
                    } catch (Exception e) {
                        estadoActualizacion = "No se pudo descargar: " + Facebook.describir(e)
                                + ". Use \"Subir APK\" desde la PC o el teléfono.";
                    }
                }
            }, "actualizar").start();
            json(out, 200, "{\"ok\":true}", null);
            return;
        }
        json(out, 404, "{\"error\":\"no-existe\"}", null);
    }

    private void abrirInstalador(File apk) {
        apk.setReadable(true, false); // el instalador de Android es otra app: tiene que poder leerlo
        Instalador i = instalador;
        if (i == null) { estadoActualizacion = "La app no está lista para instalar."; return; }
        i.instalar(apk);
        estadoActualizacion = "Listo: en la pantalla de la tablet toque «Instalar».";
    }

    private static Map<String, String> formulario(String cuerpo) throws IOException {
        Map<String, String> m = new HashMap<String, String>();
        for (String par : cuerpo.split("&")) {
            int igual = par.indexOf('=');
            if (igual > 0) m.put(URLDecoder.decode(par.substring(0, igual), "UTF-8"), URLDecoder.decode(par.substring(igual + 1), "UTF-8"));
        }
        return m;
    }

    /** Primera vez (todavía no hay PIN): se crea desde el teléfono y se guarda en fechas.txt. */
    private synchronized void crearPin(Peticion p, OutputStream out) throws Exception {
        if (pin != null) { json(out, 409, "{\"error\":\"Ya hay un PIN.\"}", null); return; }
        String nuevo = new String(leerCuerpo(p, 100), "UTF-8").trim();
        if (!nuevo.matches("\\d{4,12}")) { json(out, 400, "{\"error\":\"El PIN debe tener de 4 a 12 números.\"}", null); return; }
        File actual = carpetas.fechasActual();
        List<String> lineas = new ArrayList<String>();
        if (actual != null) lineas.addAll(MainActivity.leerLineas(actual));
        boolean puesto = false;
        for (int i = 0; i < lineas.size(); i++) {
            if (lineas.get(i).trim().matches("#?\\s*pin\\s*=.*")) { lineas.set(i, "pin = " + nuevo); puesto = true; break; }
        }
        if (!puesto) lineas.add(0, "pin = " + nuevo);
        escribir(carpetas.fechasTelefono(), unirLineas(lineas).getBytes("UTF-8"));
        pin = nuevo;
        carpetas.cambio = true;
        json(out, 200, "{\"ok\":true}", "Set-Cookie: pr=" + token(nuevo) + "; Max-Age=31536000; Path=/; HttpOnly\r\n");
    }

    // ------------------------------------------------------------------ HTTP

    private static Peticion leer(InputStream in) throws IOException {
        String primera = linea(in);
        if (primera == null || primera.isEmpty()) return null;
        String[] partes = primera.split(" ");
        if (partes.length < 2) return null;
        Peticion p = new Peticion();
        p.metodo = partes[0].toUpperCase(Locale.ROOT);
        String url = partes[1];
        int q = url.indexOf('?');
        p.ruta = q < 0 ? url : url.substring(0, q);
        if (q >= 0) for (String par : url.substring(q + 1).split("&")) {
            int igual = par.indexOf('=');
            if (igual <= 0) continue;
            p.params.put(URLDecoder.decode(par.substring(0, igual), "UTF-8"),
                    URLDecoder.decode(par.substring(igual + 1), "UTF-8"));
        }
        String l;
        while ((l = linea(in)) != null && !l.isEmpty()) {
            int dos = l.indexOf(':');
            if (dos > 0) p.cabeceras.put(l.substring(0, dos).trim().toLowerCase(Locale.ROOT), l.substring(dos + 1).trim());
        }
        try { p.largo = Long.parseLong(p.cabeceras.containsKey("content-length") ? p.cabeceras.get("content-length") : "0"); }
        catch (NumberFormatException e) { p.largo = 0; }
        p.cuerpo = in;
        return p;
    }

    private static String linea(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 16384) throw new IOException("línea muy larga");
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    private static byte[] leerCuerpo(Peticion p, long maximo) throws IOException {
        if (p.largo > maximo) throw new IOException("archivo muy grande");
        byte[] datos = new byte[(int) p.largo];
        int leidos = 0;
        while (leidos < datos.length) {
            int n = p.cuerpo.read(datos, leidos, datos.length - leidos);
            if (n < 0) throw new IOException("subida incompleta");
            leidos += n;
        }
        return datos;
    }

    private static String cookie(Peticion p, String nombre) {
        String c = p.cabeceras.get("cookie");
        if (c == null) return "";
        for (String parte : c.split(";")) {
            String t = parte.trim();
            if (t.startsWith(nombre + "=")) return t.substring(nombre.length() + 1);
        }
        return "";
    }

    private static void json(OutputStream out, int codigo, String cuerpo, String extra) throws IOException {
        responder(out, codigo, "application/json; charset=utf-8", cuerpo.getBytes("UTF-8"), extra);
    }

    private static void responder(OutputStream out, int codigo, String tipo, byte[] cuerpo, String extra) throws IOException {
        String estado = codigo == 200 ? "OK" : codigo == 401 ? "Unauthorized" : codigo == 403 ? "Forbidden"
                : codigo == 404 ? "Not Found" : "Error";
        String cab = "HTTP/1.1 " + codigo + " " + estado + "\r\n"
                + "Content-Type: " + tipo + "\r\n"
                + "Content-Length: " + cuerpo.length + "\r\n"
                + "Connection: close\r\n"
                + (extra == null ? "Cache-Control: no-store\r\n" : extra)
                + "\r\n";
        out.write(cab.getBytes("UTF-8"));
        out.write(cuerpo);
    }

    // ------------------------------------------------------------------ utilidades

    private byte[] leerAsset(String nombre) throws IOException {
        InputStream in = ctx.getAssets().open(nombre);
        try { return leerTodo(in); } finally { in.close(); }
    }

    private static byte[] leerArchivo(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try { return leerTodo(in); } finally { in.close(); }
    }

    private static byte[] leerTodo(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }

    private static void escribir(File destino, byte[] datos) throws IOException {
        destino.getParentFile().mkdirs();
        File tmp = new File(destino.getAbsolutePath() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try { out.write(datos); } finally { out.close(); }
        if (destino.exists()) destino.delete();
        if (!tmp.renameTo(destino)) throw new IOException("no se pudo guardar " + destino.getName());
    }

    /** Tipo real del archivo según sus primeros bytes (no según el nombre). */
    private static String extension(byte[] d) {
        if (d.length > 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8) return ".jpg";
        if (d.length > 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') return ".png";
        if (d.length > 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') return ".webp";
        return null;
    }

    private static String limpiar(String n) {
        if (n == null) return "";
        String s = Agenda.normalizar(n).replace(' ', '-').replaceAll("[^a-z0-9._\\-]", "");
        return s.length() > 60 ? s.substring(0, 60) : s;
    }

    private static String token(String pin) {
        return md5("portarretratos|" + pin + "|cr");
    }

    private static String md5(String s) {
        try {
            byte[] h = MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private static String texto(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20) sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    private static String unirLineas(List<String> lineas) {
        StringBuilder sb = new StringBuilder();
        for (String l : lineas) sb.append(l).append('\n');
        return sb.toString();
    }

    private static void dormir(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
