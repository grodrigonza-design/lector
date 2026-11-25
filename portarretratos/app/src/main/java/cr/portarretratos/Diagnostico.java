package cr.portarretratos;

import java.io.File;
import java.io.FileInputStream;

/** Mira los primeros bytes de un archivo para saber qué formato de imagen es en realidad. */
final class Diagnostico {
    private Diagnostico() { }

    static String tipo(File f) {
        byte[] b = new byte[16];
        int n = 0;
        try {
            FileInputStream in = new FileInputStream(f);
            try { n = in.read(b); } finally { in.close(); }
        } catch (Exception e) { return "no se pudo leer"; }
        if (n < 12) return "vacío o incompleto (" + Math.max(n, 0) + " bytes)";
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) return "JPEG";
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "PNG";
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P')
            return "WEBP";
        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F') return "GIF";
        if (b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') {
            String marca = new String(b, 8, 4);
            return marca.startsWith("avi") ? "AVIF" : marca.startsWith("hei") || marca.startsWith("mif") ? "HEIC" : "ftyp-" + marca;
        }
        if (b[0] == '<' || b[0] == '{') return "texto/página web, no imagen";
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < 8; i++) hex.append(String.format("%02x", b[i] & 0xFF));
        return "desconocido " + hex;
    }

    static boolean abrible(String tipo) {
        return tipo.equals("JPEG") || tipo.equals("PNG") || tipo.equals("WEBP") || tipo.equals("GIF");
    }
}
