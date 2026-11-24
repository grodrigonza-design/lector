package cr.portarretratos;

/**
 * Cálculos sin Android (para poder probarlos en la computadora):
 * encontrar el hueco de un marco y decidir cómo acomodar cada foto.
 */
public final class Hueco {
    private Hueco() { }

    /** Diferencia máxima (suma R+G+B) para considerar un píxel del mismo color que el centro. */
    static final int TOLERANCIA = 150;
    /** Borde suave (antialias) alrededor del hueco: también se vuelve transparente. */
    static final int TOLERANCIA_BORDE = 300;

    /**
     * Rellena desde el centro de la imagen. Si el centro es transparente, mide el área transparente.
     * Si el centro es de un color sólido (verde, blanco…), lo vuelve transparente (modifica px).
     *
     * @return {izq, arriba, der, abajo, textoArriba, textoAbajo} del hueco (der/abajo exclusivos), o null si no hay hueco útil.
     */
    public static int[] abrir(int[] px, int w, int h) {
        int centro = px[(h / 2) * w + w / 2];
        boolean yaTransparente = (centro >>> 24) < 128;
        byte[] visto = new byte[w * h];
        int[] cola = new int[w * h];
        int ini = 0, fin = 0;
        cola[fin++] = (h / 2) * w + w / 2;
        visto[(h / 2) * w + w / 2] = 1;
        int x0 = w, y0 = h, x1 = -1, y1 = -1, total = 0;
        while (ini < fin) {
            int i = cola[ini++];
            int x = i % w, y = i / w;
            total++;
            if (x < x0) x0 = x; if (x > x1) x1 = x;
            if (y < y0) y0 = y; if (y > y1) y1 = y;
            if (!yaTransparente) px[i] = 0;
            for (int k = 0; k < 4; k++) {
                int nx = x + (k == 0 ? 1 : k == 1 ? -1 : 0), ny = y + (k == 2 ? 1 : k == 3 ? -1 : 0);
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                int j = ny * w + nx;
                if (visto[j] != 0) continue;
                int c = px[j];
                if (yaTransparente) {
                    if ((c >>> 24) < 128) { visto[j] = 1; cola[fin++] = j; }
                } else {
                    int d = distancia(c, centro);
                    if (d <= TOLERANCIA && (c >>> 24) > 200) { visto[j] = 1; cola[fin++] = j; }
                    else if (d <= TOLERANCIA_BORDE) { visto[j] = 2; px[j] = 0; } // borde suave: borrar sin expandir
                }
            }
        }
        // Un "hueco" de menos del 15 % de la imagen no es un hueco: probablemente el centro es parte del dibujo.
        if (total < w * h * 0.15) return null;
        int[] seguro = zonaLibre(px, w, x0, y0, x1 + 1, y1 + 1);
        return new int[]{x0, y0, x1 + 1, y1 + 1, seguro[0], seguro[1]};
    }

    /**
     * Filas donde la franja central del hueco está libre de adornos (pasteles, moños…),
     * para poner ahí el mensaje sin taparlos. Devuelve {primera fila libre arriba, última fila libre abajo + 1}.
     */
    static int[] zonaLibre(int[] px, int w, int x0, int y0, int x1, int y1) {
        int margen = (x1 - x0) / 5, a = x0 + margen, b = x1 - margen;
        int arriba = y0, abajo = y1;
        int alto = y1 - y0;
        // ignorar las esquinas redondeadas: buscar desde un 4 % hacia dentro como máximo hasta la mitad
        for (int y = y0; y < y0 + alto / 2; y++) { if (libre(px, w, y, a, b)) { arriba = y; break; } }
        for (int y = y1 - 1; y > y0 + alto / 2; y--) { if (libre(px, w, y, a, b)) { abajo = y + 1; break; } }
        return new int[]{arriba, abajo};
    }

    private static boolean libre(int[] px, int w, int y, int a, int b) {
        int abiertos = 0;
        for (int x = a; x < b; x++) if ((px[y * w + x] >>> 24) < 128) abiertos++;
        return abiertos >= (b - a) * 0.98;
    }

    static int distancia(int a, int b) {
        return Math.abs(((a >> 16) & 255) - ((b >> 16) & 255))
             + Math.abs(((a >> 8) & 255) - ((b >> 8) & 255))
             + Math.abs((a & 255) - (b & 255));
    }

    /**
     * ¿Recortar la foto para llenar el hueco, o mostrarla completa con fondo difuminado?
     * "inteligente": recorta solo si se pierde poco (≤ 30 %); así las verticales se ven enteras.
     */
    public static boolean recortar(int fotoAncho, int fotoAlto, int huecoAncho, int huecoAlto, String ajuste) {
        if ("recortar".equals(ajuste)) return true;
        if ("completa".equals(ajuste)) return false;
        float rf = fotoAncho / (float) fotoAlto, rh = huecoAncho / (float) huecoAlto;
        float seQueda = rf > rh ? rh / rf : rf / rh;
        return seQueda >= 0.70f;
    }
}
