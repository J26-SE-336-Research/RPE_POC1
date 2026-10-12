package org.rrpe.experiment;

import java.nio.charset.StandardCharsets;

public final class EncodingDemo {

    public static void main(String[] args) {
        ResilienceContext context = new ResilienceContext(
                1,
                "4bf92f3577b34da6a3ce929d0e0e4736",
                1791655200000L,
                2,
                0,
                false);

        String encoded = DelimitedContextCodec.encode(context);

        System.out.println("Header value: " + encoded);
        System.out.println(
                "Header-value bytes: "
                        + encoded.getBytes(StandardCharsets.US_ASCII).length);
    }
}