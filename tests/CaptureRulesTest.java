package dev.ex.capture;
import java.util.Set;
public class CaptureRulesTest {
 public static void main(String[] args) {
  CaptureRules r=new CaptureRules();
  for(int i=0;i<35;i++) r.advance(Set.of("red"),60);
  assert r.progress==35 && r.owner.equals("red");
  r.advance(Set.of("red","blue"),60); r.advance(Set.of(),60);
  assert r.progress==35;
  for(int i=0;i<35;i++) r.advance(Set.of("blue"),60);
  assert r.progress==0 && r.owner.equals("blue");
  for(int i=0;i<60;i++) r.advance(Set.of("blue"),60);
  assert r.progress==60 && r.owner.equals("blue");
  r.advance(Set.of("red"),60); assert r.progress==60;
  r.reset(); assert r.progress==0 && r.owner==null;
 }
}
