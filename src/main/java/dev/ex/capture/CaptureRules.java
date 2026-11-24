package dev.ex.capture;
import java.util.Set;
public final class CaptureRules {
 String owner;
 int progress;
 void reset() { owner = null; progress = 0; }
 void advance(Set<String> occupants, int duration) {
  if (occupants.size() != 1 || progress >= duration) return;
  String team = occupants.iterator().next();
  if (progress == 0 || team.equals(owner)) { owner = team; progress++; }
  else { progress--; if (progress == 0) owner = team; }
 }
}
