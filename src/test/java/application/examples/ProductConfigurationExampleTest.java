package application.examples;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.ants.jndd.diagram.NDD;
import org.junit.jupiter.api.Test;

class ProductConfigurationExampleTest {
    @Test
    void buildsTheDocumentedConfigurationSet() {
        for (NDD.LabelMode mode : NDD.LabelMode.values()) {
            ProductConfigurationExample.Model model = ProductConfigurationExample.buildModel(mode);
            try {
                assertEquals(8.0, NDD.satCount(model.valid), "mode=" + mode);

                int blue = NDD.ref(NDD.encodePrefix(new int[]{0, 1}, model.color));
                int blueConfigurations = NDD.ref(NDD.and(model.valid, blue));
                NDD.deref(blue);
                try {
                    assertEquals(3.0, NDD.satCount(blueConfigurations), "mode=" + mode);
                    int[][] assignment = NDD.anySat(blueConfigurations);
                    assertArrayEquals(new int[]{0, 1}, assignment[model.color]);
                    assertArrayEquals(new int[]{0}, assignment[model.memory]);
                    assertArrayEquals(new int[]{0}, assignment[model.storage]);
                } finally {
                    NDD.deref(blueConfigurations);
                }
            } finally {
                NDD.deref(model.valid);
            }
        }
    }
}
