/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.domain.insurance.underwriting.infra.staff;

import java.net.URI;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("insurance.underwriting.staff-client")
public record StaffUnderwritingSettings(boolean enabled,URI endpoint,String issuer,URI jwkSetUri,String domainAudience,
        String coreAudience,String inboundClientId,String delegatingClientId,String registrationId,String platformTenant,
        boolean allowLoopbackHttp){
    public StaffUnderwritingSettings{
        if(enabled){
            for(String value:List.of(issuer,domainAudience,coreAudience,inboundClientId,delegatingClientId,registrationId,platformTenant))
                if(value.isBlank()||value.length()>512||value.codePoints().anyMatch(Character::isISOControl))throw invalid();
            for(URI value:List.of(endpoint,URI.create(issuer),jwkSetUri))secure(value,allowLoopbackHttp);
            if(!Set.of("","/").contains(endpoint.getRawPath())||domainAudience.equals(coreAudience)||inboundClientId.equals(delegatingClientId))throw invalid();
        }
    }
    public static void secure(URI value,boolean loopback){
        if(value==null||value.getHost()==null||value.getRawUserInfo()!=null||value.getRawQuery()!=null||value.getRawFragment()!=null||!value.equals(value.normalize())
            ||!("https".equals(value.getScheme())||loopback&&"http".equals(value.getScheme())&&Set.of("127.0.0.1","localhost","::1","[::1]").contains(value.getHost())))throw invalid();
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Explicit STAFF underwriting caller and owner configuration required");}
    @Override public String toString(){return "StaffUnderwritingSettings[redacted]";}
}
