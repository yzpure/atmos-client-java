package com.emc.atmos.sync.util;

import com.emc.atmos.api.ObjectId;
import com.emc.atmos.api.bean.Metadata;
import org.apache.log4j.LogMF;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public final class AtmosUtil {
    private static final Logger l4j = Logger.getLogger( AtmosUtil.class );

    public static List<Metadata> getRetentionMetadataForUpdate( AtmosMetadata metadata ) {
        List<Metadata> list = new ArrayList<Metadata>();

        ObjectId id = new ObjectId( metadata.getSystemMetadata().get( "objectid" ).getValue() );

        Boolean retentionEnabled = metadata.isRetentionEnabled();
        Date retentionEnd = metadata.getRetentionEndDate();

        if ( retentionEnd != null ) {
            LogMF.debug( l4j, "Retention {0} (OID: {1}, end-date: {2})", retentionEnabled ? "enabled" : "disabled",
                         id, Iso8601Util.format( retentionEnd ) );
            list.add( new Metadata( "user.maui.retentionEnable", retentionEnabled.toString(), false ) );
            list.add( new Metadata( "user.maui.retentionEnd", Iso8601Util.format( retentionEnd ), false ) );
        }

        return list;
    }

    public static List<Metadata> getExpirationMetadataForUpdate( AtmosMetadata metadata ) {
        List<Metadata> list = new ArrayList<Metadata>();

        ObjectId id = new ObjectId( metadata.getSystemMetadata().get( "objectid" ).getValue() );

        Boolean expirationEnabled = metadata.isExpirationEnabled();
        Date expiration = metadata.getExpirationDate();

        if ( expiration != null ) {
            LogMF.debug( l4j, "Expiration {0} (OID: {1}, end-date: {2})", expirationEnabled ? "enabled" : "disabled",
                         id, Iso8601Util.format( expiration ) );
            list.add( new Metadata( "user.maui.expirationEnable", expirationEnabled.toString(), false ) );
            list.add( new Metadata( "user.maui.expirationEnd", Iso8601Util.format( expiration ), false ) );
        }

        return list;
    }

    private AtmosUtil() {
    }
}
