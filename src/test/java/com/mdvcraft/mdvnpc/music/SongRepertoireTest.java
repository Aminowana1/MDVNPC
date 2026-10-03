package com.mdvcraft.mdvnpc.music;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SongRepertoireTest {
    private static final List<String> ADDITIONS=List.of("romeria","cuervo","roble","meson","puerto");

    @Test void allEightSongsLoadBothInstrumentsAndKeepTheOriginalRepertoire() {
        assertEquals(8,MusicService.REPERTOIRE.size());
        assertEquals(List.of("tourdion","jabali","farol"),MusicService.REPERTOIRE.subList(0,3));
        for(String name:MusicService.REPERTOIRE) {
            Song song=Song.load(name);
            assertTrue(song.frames().values().stream().flatMap(List::stream).anyMatch(Song.Note::flute),name);
            assertTrue(song.frames().values().stream().flatMap(List::stream).anyMatch(note->!note.flute()),name);
        }
        assertEquals(288,Song.load("tourdion").duration());
        assertEquals(196,Song.load("jabali").duration());
        assertEquals(192,Song.load("farol").duration());
    }

    @Test void newSongsHaveThirtySecondTimelinesWithCoordinatedIntroductionsAndCadences() {
        for(String name:ADDITIONS) {
            Song song=Song.load(name);
            assertEquals(name.equals("puerto")?640:600,song.duration(),name);
            assertTrue(song.frames().get(0).stream().anyMatch(Song.Note::flute),name);
            assertTrue(song.frames().get(0).stream().anyMatch(note->!note.flute()),name);
            int last=Collections.max(song.frames().keySet());
            assertEquals(switch(name){case "cuervo","meson"->594;case "puerto"->632;default->592;},last,name);
            var ending=song.frames().get(last);
            float flute=ending.stream().filter(Song.Note::flute).findFirst().orElseThrow().pitch();
            assertTrue(ending.stream().anyMatch(note->!note.flute() && Math.abs(note.pitch()-flute)<.00001),name);
        }
    }

    @Test void guitarCarriesTheWholeMelodyWhenPlayingWithoutAFlutist() {
        for(String name:ADDITIONS) {
            Song song=Song.load(name);
            for(var frame:song.frames().entrySet())
                for(var note:frame.getValue())if(note.flute())
                    assertTrue(frame.getValue().stream().anyMatch(guitar->!guitar.flute()
                            && Math.abs(guitar.pitch()-note.pitch())<.00001),name+" tick "+frame.getKey());
        }
    }

    @Test void newScoresStayWithinNoteRangesAndLimitAudioEvents() {
        for(String name:ADDITIONS) {
            Song song=Song.load(name);
            int quantum=switch(name){case "romeria","puerto"->8;case "cuervo","meson"->6;default->4;};
            int guitars=0;
            for(var frame:song.frames().entrySet()) {
                assertTrue(frame.getKey()>=0 && frame.getKey()<song.duration(),name);
                assertEquals(0,frame.getKey()%quantum,name);
                assertTrue(frame.getValue().size()<=3,name);
                for(var note:frame.getValue()) {
                    assertTrue(Float.isFinite(note.pitch()) && note.pitch()>=.5 && note.pitch()<=2,name);
                    assertTrue(Float.isFinite(note.volume()) && note.volume()>0 && note.volume()<=1,name);
                    if(!note.flute())guitars++;
                }
            }
            assertTrue(guitars<=180,name+" guitar event count "+guitars);
        }
    }

    @Test void eachAdditionUsesDistinctMelodiesAndDevelopsItsPhrases() {
        Set<List<String>> tunes=new HashSet<>();
        for(String name:ADDITIONS) {
            Song song=Song.load(name);
            List<String> tune=song.frames().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .flatMap(frame->frame.getValue().stream().filter(Song.Note::flute)
                            .map(note->frame.getKey()+":"+note.pitch())).toList();
            assertTrue(tunes.add(tune),name);
            int barTicks=switch(name){case "romeria","puerto"->48;case "cuervo","meson"->36;default->32;};
            Set<List<String>> bars=new HashSet<>();
            for(int bar=0;bar<(name.equals("puerto")?624:576)/barTicks;bar++) {
                final int from=bar*barTicks;
                bars.add(song.frames().entrySet().stream().filter(frame->frame.getKey()>=from && frame.getKey()<from+barTicks)
                        .sorted(Map.Entry.comparingByKey()).flatMap(frame->frame.getValue().stream().filter(Song.Note::flute)
                                .map(note->(frame.getKey()-from)+":"+note.pitch())).toList());
            }
            assertTrue(bars.size()>=8,name+" different melodic bars "+bars.size());
        }
    }

    @Test void everyInstrumentBeginsImmediatelyAndHasNoUnexpectedLongSilentPart() {
        for(String name:MusicService.REPERTOIRE) {
            Song song=Song.load(name);
            for(boolean flute:List.of(true,false)) {
                var ticks=song.frames().entrySet().stream()
                        .filter(frame->frame.getValue().stream().anyMatch(note->note.flute()==flute && note.volume()>0))
                        .map(Map.Entry::getKey).sorted().toList();
                assertEquals(0,ticks.getFirst(),name+" opening "+flute);
                for(int i=1;i<ticks.size();i++)assertTrue(ticks.get(i)-ticks.get(i-1)<=24,name+" unexpected gap "+flute);
                assertTrue(song.duration()-ticks.getLast()<=24,name+" silent ending "+flute);
            }
        }
    }
}
