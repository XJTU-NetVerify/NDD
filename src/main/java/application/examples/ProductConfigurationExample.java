package application.examples;

import java.util.Arrays;

import org.ants.jndd.diagram.NDD;

/**
 * A small, non-network example that models valid product configurations.
 *
 * <p>The fields are color (two bits), memory (one bit), and storage (one bit).
 * The unused fourth color is excluded, green requires 16 GB of memory, and
 * configurations with 8 GB of memory require 256 GB of storage.</p>
 */
public final class ProductConfigurationExample {
    private static final String[] COLORS = {"red", "blue", "green"};
    private static final String[] MEMORY = {"8 GB", "16 GB"};
    private static final String[] STORAGE = {"256 GB", "512 GB"};

    private ProductConfigurationExample() {
    }

    public static void main(String[] args) {
        NDD.LabelMode mode = args.length == 0
                ? NDD.LabelMode.BDD
                : NDD.LabelMode.valueOf(args[0].toUpperCase());
        Model model = buildModel(mode);
        try {
            System.out.println("Label backend: " + mode);
            System.out.println("Valid configurations: " + (long) NDD.satCount(model.valid));

            int blue = fieldValue(model.color, 2, 1);
            int blueConfigurations = NDD.ref(NDD.and(model.valid, blue));
            NDD.deref(blue);
            try {
                System.out.println("Valid blue configurations: "
                        + (long) NDD.satCount(blueConfigurations));
                System.out.println("One valid blue configuration: "
                        + formatAssignment(NDD.anySat(blueConfigurations), model));
            } finally {
                NDD.deref(blueConfigurations);
            }
        } finally {
            NDD.deref(model.valid);
        }
    }

    static Model buildModel() {
        return buildModel(NDD.LabelMode.BDD);
    }

    static Model buildModel(NDD.LabelMode mode) {
        NDD.initNDD(10_000, 2_000, 10_000, 2_000, mode);
        int color = NDD.declareField(2);
        int memory = NDD.declareField(1);
        int storage = NDD.declareField(1);
        NDD.generateFields();

        // color uses two bits, so exclude binary 11 from the three-value domain.
        int unusedColor = fieldValue(color, 2, 3);
        int validColors = NDD.ref(NDD.not(unusedColor));
        NDD.deref(unusedColor);

        int green = fieldValue(color, 2, 2);
        int memory16 = fieldValue(memory, 1, 1);
        int greenRequires16 = NDD.ref(NDD.imp(green, memory16));
        NDD.deref(green);
        NDD.deref(memory16);

        int memory8 = fieldValue(memory, 1, 0);
        int storage256 = fieldValue(storage, 1, 0);
        int memory8Requires256 = NDD.ref(NDD.imp(memory8, storage256));
        NDD.deref(memory8);
        NDD.deref(storage256);

        int valid = NDD.ref(NDD.and(validColors, greenRequires16));
        valid = NDD.andTo(valid, memory8Requires256);
        NDD.deref(validColors);
        NDD.deref(greenRequires16);
        NDD.deref(memory8Requires256);
        return new Model(color, memory, storage, valid);
    }

    private static int fieldValue(int field, int width, int value) {
        int[] bits = new int[width];
        for (int bit = 0; bit < width; bit++) {
            bits[bit] = (value >>> (width - 1 - bit)) & 1;
        }
        return NDD.ref(NDD.encodePrefix(bits, field));
    }

    private static String formatAssignment(int[][] assignment, Model model) {
        int color = binaryValue(assignment[model.color]);
        int memory = binaryValue(assignment[model.memory]);
        int storage = binaryValue(assignment[model.storage]);
        return COLORS[color] + ", " + MEMORY[memory] + ", " + STORAGE[storage];
    }

    private static int binaryValue(int[] bits) {
        return Arrays.stream(bits).reduce(0, (value, bit) -> (value << 1) | bit);
    }

    static final class Model {
        final int color;
        final int memory;
        final int storage;
        final int valid;

        Model(int color, int memory, int storage, int valid) {
            this.color = color;
            this.memory = memory;
            this.storage = storage;
            this.valid = valid;
        }
    }
}
