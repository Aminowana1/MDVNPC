package com.mdvcraft.mdvnpc.music;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Immutable, pre-indexed score. Both instruments use exactly the same tick timeline. */
public record Song(String name, int duration, Map<Integer,List<Note>> frames) {
    public record Note(boolean flute, float pitch, float volume) {}
    public static Song load(String name) {
        try(var stream=Objects.requireNonNull(Song.class.getResourceAsStream("/music/"+name+".csv"));
            var reader=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8))) {
            int duration=Integer.parseInt(reader.readLine());
            Map<Integer,List<Note>> frames=new HashMap<>();
            String line;
            while((line=reader.readLine())!=null) {
                var p=line.split(","); int tick=Integer.parseInt(p[0]);
                float pitch=Float.parseFloat(p[2]),volume=Float.parseFloat(p[3]);
                if(tick<0 || tick>=duration || !Float.isFinite(pitch) || pitch<.5 || pitch>2
                        || !Float.isFinite(volume) || volume<0 || volume>1 || !Set.of("F","G").contains(p[1]))
                    throw new IllegalArgumentException("Invalid score: "+name);
                frames.computeIfAbsent(tick,k->new ArrayList<>()).add(new Note(p[1].equals("F"),pitch,volume));
            }
            frames.replaceAll((k,v)->List.copyOf(v));
            return new Song(name,duration,Map.copyOf(frames));
        } catch(IOException ex) { throw new UncheckedIOException(ex); }
    }
}
