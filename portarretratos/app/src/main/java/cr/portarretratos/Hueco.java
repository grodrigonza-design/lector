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
     * @return {izq, arriba, der, abajo, textoArriba, textoAbajo, anchoTextoAbajo, anchoTextoArriba} del hueco (der/abajo exclusivos), o null si no hay hueco útil.
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
        // Borde real del hueco: filas/columnas con buena parte abierta. Así no cuentan
        // filtraciones finas del verde hacia adornos verdes del marco (montañas, hojas…).
        int[] porFila = new int[h], porCol = new int[w];
        for (int y = y0; y <= y1; y++)
            for (int x = x0; x <= x1; x++)
                if (visto[y * w + x] != 0) { porFila[y]++; porCol[x]++; }
        int maxFila = 0, maxCol = 0;
        for (int v : porFila) maxFila = Math.max(maxFila, v);
        for (int v : porCol) maxCol = Math.max(maxCol, v);
        while (y0 < y1 && porFila[y0] < maxFila * 0.5) y0++;
        while (y1 > y0 && porFila[y1] < maxFila * 0.5) y1--;
        while (x0 < x1 && porCol[x0] < maxCol * 0.5) x0++;
        while (x1 > x0 && porCol[x1] < maxCol * 0.5) x1--;
        int[] seguro = zonaLibre(px, w, x0, y0, x1 + 1, y1 + 1);
        int banda = (int) (h * 0.13f); // alto aproximado de una línea de mensaje
        int anchoAbajo = anchoLibre(px, w, Math.max(y0, seguro[1] - banda), seguro[1], x0, x1 + 1);
        int anchoArriba = anchoLibre(px, w, seguro[0], Math.min(y1 + 1, seguro[0] + banda), x0, x1 + 1);
        return new int[]{x0, y0, x1 + 1, y1 + 1, seguro[0], seguro[1], anchoAbajo, anchoArriba};
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

    /**
     * Ancho libre centrado entre las filas ya..yb (sin chocar con calabazas, regalos… de las esquinas).
     * Una columna cuenta como ocupada si tiene más de 2 píxeles del marco en esa franja
     * (así unos puntitos sueltos no lo arruinan).
     */
    static int anchoLibre(int[] px, int w, int ya, int yb, int x0, int x1) {
        int cx = (x0 + x1) / 2, medio = (x1 - x0) / 2, i = 0;
        while (i < medio && !columnaOcupada(px, w, cx - i, ya, yb) && !columnaOcupada(px, w, cx + i, ya, yb)) i++;
        return i * 2;
    }

    private static boolean columnaOcupada(int[] px, int w, int x, int ya, int yb) {
        int ocupados = 0;
        for (int y = ya; y < yb; y++) if ((px[y * w + x] >>> 24) >= 128 && ++ocupados > 2) return true;
        return false;
    }

    private static boolean libre(int[] px, int w, int y, int a, int b) {
        // Libre = casi sin píxeles del marco (tolera 3 puntitos sueltos, no la punta de una vela).
        int ocupados = 0, tolera = Math.max(3, (b - a) / 200);
        for (int x = a; x < b; x++) if ((px[y * w + x] >>> 24) >= 128 && ++ocupados > tolera) return false;
        return true;
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
