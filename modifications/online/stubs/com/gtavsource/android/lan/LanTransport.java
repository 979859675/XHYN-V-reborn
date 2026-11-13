// Compile-time stub of the game's class; never packaged.
package com.gtavsource.android.lan;

public final class LanTransport {
    public interface Source {
        void disconnected();
        void receive(byte[] frame);
        byte[] snapshot();
    }
    public static LanTransport join(String address, String name, String code, Source source, boolean virtualNetwork) { throw new UnsupportedOperationException(); }
    public void start() { throw new UnsupportedOperationException(); }
    public void close() { throw new UnsupportedOperationException(); }
    public boolean running() { throw new UnsupportedOperationException(); }
    public boolean connected() { throw new UnsupportedOperationException(); }
    public String status() { throw new UnsupportedOperationException(); }
}
