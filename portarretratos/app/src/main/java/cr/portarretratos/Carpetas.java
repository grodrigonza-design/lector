package cr.portarretratos;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Dónde están las cosas.
 *
 * - base: la carpeta Portarretratos de la tarjeta SD (se llena desde la computadora).
 *   En Android 4.4 las apps NO pueden escribir en la SD, solo leer.
 * - propias: carpetas de la app donde Android sí deja escribir (en la SD:
 *   Android/data/cr.portarretratos/files). Ahí van las fotos y marcos que llegan
 *   desde el teléfono, el fechas.txt editado desde el teléfono y la lista de fotos ocultas.
 *   OJO: Android borra estas carpetas si se desinstala la app.
 */
final class Carpetas {

    private final Context ctx;
    volatile File base;
    /** Se pone en true cuando algo cambió (foto subida, borrada…) para volver a leer las carpetas. */
    volatile boolean cambio = true;

    Carpetas(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    /** Todas las carpetas propias (memoria interna y tarjeta SD), para leer. */
    List<File> propias() {
        List<File> res = new ArrayList<File>();
        File[] dirs = ctx.getExternalFilesDirs(null);
        if (dirs != null) for (File d : dirs) if (d != null) res.add(d);
        if (res.isEmpty()) res.add(ctx.getFilesDir());
        return res;
    }

    /** Carpeta propia donde se escribe: la de la tarjeta SD si existe (la última de la lista). */
    File propiaEscritura() {
        List<File> ps = propias();
        File base = this.base;
        if (base != null && base.getParentFile() != null) {
            String raiz = base.getParentFile().getAbsolutePath() + "/";
            for (File p : ps) if (p.getAbsolutePath().startsWith(raiz)) return p;
        }
        return ps.get(ps.size() - 1);
    }

    File fotosTelefono() { File d = new File(propiaEscritura(), "fotos"); d.mkdirs(); return d; }
    File marcosTelefono() { File d = new File(propiaEscritura(), "marcos"); d.mkdirs(); return d; }
    File miniaturas() { File d = new File(propiaEscritura(), "miniaturas"); d.mkdirs(); return d; }
    File fechasTelefono() { return new File(propiaEscritura(), "fechas.txt"); }

    /** El fechas.txt más reciente: el de la tarjeta o el editado desde el teléfono. */
    File fechasActual() {
        File mejor = null;
        List<File> candidatos = new ArrayList<File>();
        if (base != null) candidatos.add(new File(base, "fechas.txt"));
        for (File p : propias()) candidatos.add(new File(p, "fechas.txt"));
        for (File f : candidatos)
            if (f.isFile() && (mejor == null || f.lastModified() > mejor.lastModified())) mejor = f;
        return mejor;
    }

    /** Carpetas de fotos válidas (para leer y para validar lo que pide el teléfono). */
    List<File> carpetasFotos() {
        List<File> res = new ArrayList<File>();
        if (base != null) res.add(new File(base, "fotos"));
        for (File p : propias()) res.add(new File(p, "fotos"));
        return res;
    }

    List<File> carpetasMarcos() {
        List<File> res = new ArrayList<File>();
        if (base != null) res.add(new File(base, "marcos"));
        for (File p : propias()) res.add(new File(p, "marcos")); // los del teléfono ganan si se llaman igual
        return res;
    }

    /** Todas las fotos, sin las ocultas. */
    List<File> fotos() {
        Set<String> ocultas = ocultas();
        List<File> res = new ArrayList<File>();
        for (File d : carpetasFotos()) juntar(d, res, 0);
        if (!ocultas.isEmpty()) {
            List<File> visibles = new ArrayList<File>();
            for (File f : res) if (!ocultas.contains(f.getAbsolutePath())) visibles.add(f);
            res = visibles;
        }
        return res;
    }

    /** nombre de archivo → archivo. */
    Map<String, File> marcos() {
        Map<String, File> res = new LinkedHashMap<String, File>();
        for (File d : carpetasMarcos()) {
            File[] hijos = d.listFiles();
            if (hijos == null) continue;
            List<File> orden = new ArrayList<File>();
            Collections.addAll(orden, hijos);
            Collections.sort(orden);
            for (File f : orden) if (f.isFile() && esImagen(f.getName())) res.put(f.getName(), f);
        }
        return res;
    }

    /** ¿Está este archivo dentro de alguna de estas carpetas? (evita que el teléfono pida archivos ajenos) */
    static boolean dentroDe(File f, List<File> carpetas) {
        try {
            String ruta = f.getCanonicalPath();
            for (File c : carpetas) if (ruta.startsWith(c.getCanonicalPath() + File.separator)) return true;
        } catch (IOException ignored) { }
        return false;
    }

    // ------------------------------------------------------------------ fotos ocultas

    /** Las fotos de la tarjeta no se pueden borrar en Android 4.4: se ocultan. */
    private File archivoOcultas() { return new File(propiaEscritura(), "ocultas.txt"); }

    Set<String> ocultas() {
        Set<String> res = new HashSet<String>();
        for (File p : propias()) {
            File f = new File(p, "ocultas.txt");
            if (f.isFile()) res.addAll(MainActivity.leerLineas(f));
        }
        res.remove("");
        return res;
    }

    synchronized void ocultar(File f) throws IOException {
        FileOutputStream out = new FileOutputStream(archivoOcultas(), true);
        try { out.write((f.getAbsolutePath() + "\n").getBytes("UTF-8")); } finally { out.close(); }
    }

    // ------------------------------------------------------------------ utilidades

    private static void juntar(File dir, List<File> salida, int nivel) {
        File[] hijos = dir.listFiles();
        if (hijos == null || nivel > 4) return;
        for (File f : hijos) {
            if (f.getName().startsWith(".")) continue;
            if (f.isDirectory()) juntar(f, salida, nivel + 1);
            else if (esImagen(f.getName())) salida.add(f);
        }
    }

    static boolean esImagen(String n) {
        String l = n.toLowerCase(Locale.ROOT);
        return !l.startsWith(".") && (l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png")
                || l.endsWith(".webp") || l.endsWith(".bmp") || l.endsWith(".gif"));
    }
}
