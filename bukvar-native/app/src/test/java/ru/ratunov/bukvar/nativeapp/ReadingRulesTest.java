package ru.ratunov.bukvar.nativeapp;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
public class ReadingRulesTest {
 @Test public void exact(){assertEquals(ReadingRules.Result.EXACT,ReadingRules.compare("мама","Мама!",null));}
 @Test public void silence(){assertEquals(ReadingRules.Result.EMPTY,ReadingRules.compare("мама"," ",null));}
 @Test public void nullInput(){assertEquals(ReadingRules.Result.EMPTY,ReadingRules.compare("мама",null,null));}
 @Test public void wrongWord(){assertEquals(ReadingRules.Result.DIFFERENT,ReadingRules.compare("мама","рама",null));}
 @Test public void addedWord(){assertEquals(ReadingRules.Result.DIFFERENT,ReadingRules.compare("мама","это мама",null));}
 @Test public void repeatedWord(){assertEquals(ReadingRules.Result.DIFFERENT,ReadingRules.compare("мама","мама мама",null));}
 @Test public void syllables(){assertEquals(ReadingRules.Result.SYLLABLES,ReadingRules.compare("мама","ма ма",new String[]{"ма","ма"}));}
 @Test public void noArbitrarySpaceRemoval(){assertEquals(ReadingRules.Result.DIFFERENT,ReadingRules.compare("мама","м а м а",null));}
 @Test public void yoIsNotE(){assertNotEquals(ReadingRules.normalize("всё"),ReadingRules.normalize("все"));}
 @Test public void acuteStress(){assertEquals("мама",ReadingRules.normalize("ма\u0301ма"));}
 @Test public void sentenceWhitespace(){assertEquals(ReadingRules.Result.EXACT,ReadingRules.compare("Мама мыла раму.","МАМА   мыла\nраму",null));}
 @Test public void courseSize(){assertEquals(9,Course.LESSONS.size());assertEquals(54,Course.size());}
 @Test public void newLettersAreIntroduced(){Set<Character> known=new HashSet<>();for(Course.Lesson l:Course.LESSONS){for(char c:l.letters.toCharArray())known.add(c);for(Course.Task t:l.tasks){if(t.kind.equals("talk"))continue;for(char c:ReadingRules.normalize(t.text).toCharArray()){if(Character.isLetter(c))assertTrue(t.text+" uses "+c,known.contains(c));}}}}
 @Test public void syllableTextMatches(){for(Course.Lesson l:Course.LESSONS)for(Course.Task t:l.tasks)if(t.syllables!=null)assertEquals(ReadingRules.normalize(t.text),ReadingRules.normalize(String.join("",t.syllables)));}
 @Test public void tasksHaveHints(){for(Course.Lesson l:Course.LESSONS)for(Course.Task t:l.tasks){assertFalse(t.hint.isEmpty());assertFalse(t.text.isEmpty());}}
}
