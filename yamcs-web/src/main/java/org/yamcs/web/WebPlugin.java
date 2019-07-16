package org.yamcs.web;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.yamcs.spi.Plugin;
import org.yamcs.yarch.Bucket;
import org.yamcs.yarch.YarchDatabase;
import org.yamcs.yarch.YarchDatabaseInstance;

public class WebPlugin implements Plugin {

    private String version;

    public WebPlugin() {
        Package pkg = getClass().getPackage();
        if (pkg != null) {
            version = pkg.getImplementationVersion();
        }
    }

    @Override
    public String getName() {
        return "yamcs-web";
    }

    @Override
    public String getDescription() {
        return "Web interface for managing and monitoring Yamcs";
    }

    @Override
    public String getVersion() {
        return version;
    }

    @Override
    public String getVendor() {
        return "Space Applications Services";
    }

    @Override
    public void onLoad() {
        YarchDatabaseInstance yarch = YarchDatabase.getInstance("_global");
        try {
            Bucket bucket = yarch.getBucket("displays");
            if (bucket == null) {
                yarch.createBucket("displays");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create displays bucket", e);
        }
    }
}