package org.factorio.local.launcher;

import java.io.*;
import java.nio.file.Files;

/** Persisted SAF grants name a fixed release/content profile, never the active import. */
final class GameDocumentIds {
    static final class Id {
        final String profile,local;
        private Id(String profile,String local){this.profile=profile;this.local=local;}
        String document(){return profile+"|"+local;}
        boolean isRoot(){return local.indexOf(':')<0;}
        String root(){int colon=local.indexOf(':');return colon<0?local:local.substring(0,colon);}
        Id child(String name) throws IOException {
            GameFiles.checkName(name);return new Id(profile,local+(isRoot()?":":"/")+name);
        }
        Id parent() {
            int slash=local.lastIndexOf('/'),colon=local.indexOf(':');
            return new Id(profile,slash>=0?local.substring(0,slash):colon>=0?local.substring(0,colon):local);
        }
    }
    static Id root(String profile,String root) throws IOException {return parse(profile+"|"+root);}
    static Id parse(String document) throws IOException {
        if(document==null)throw new IOException("Missing document identifier");
        int separator=document.indexOf('|');
        // Unscoped old document/tree grants must not follow a replacement game.
        // Logical Root IDs (saves/mods) are retained by the provider separately.
        if(separator<0||separator!=document.lastIndexOf('|'))throw new IOException("Unscoped document identifier");
        String profile=document.substring(0,separator),local=document.substring(separator+1);
        boolean expansion=profile.endsWith("-space-age");String suffix=expansion?"-space-age":"-base";
        if(!profile.startsWith("factorio-")||!profile.endsWith(suffix)||profile.length()<="factorio-".length()+suffix.length()||profile.length()>128)
            throw new IOException("Invalid document profile");
        String version=profile.substring("factorio-".length(),profile.length()-suffix.length());
        if(!GameProfiles.id(version,expansion).equals(profile))throw new IOException("Invalid document profile");
        int colon=local.indexOf(':');String root=colon<0?local:local.substring(0,colon);
        if(!root.equals("saves")&&!root.equals("mods"))throw new IOException("Unknown document root");
        if(colon>=0)for(String part:local.substring(colon+1).split("/",-1))GameFiles.checkName(part);
        return new Id(profile,local);
    }
    static File directory(File files,String profile) throws IOException {
        // Validate identifiers even for direct helper callers. Never manufacture
        // a missing historical profile merely because a stale URI names it.
        root(profile,"saves");
        File profiles=new File(files.getCanonicalFile(),"profiles"),target=new File(profiles,profile);
        if(!profiles.isDirectory()||!target.isDirectory()||Files.isSymbolicLink(profiles.toPath())||Files.isSymbolicLink(target.toPath())
                ||!profiles.getCanonicalFile().equals(profiles.getAbsoluteFile())||!target.getCanonicalFile().equals(target.getAbsoluteFile()))
            throw new FileNotFoundException("Document profile is unavailable");
        return target;
    }
}
