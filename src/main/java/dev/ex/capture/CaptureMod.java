package dev.ex.capture;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.*;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.sounds.*;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.util.*;
import static net.minecraft.commands.Commands.*;

@Mod("minigames")
public final class CaptureMod {
 private static final Gson GSON=new GsonBuilder().setPrettyPrinting().create();
 private static final org.slf4j.Logger LOG=com.mojang.logging.LogUtils.getLogger();
 private static final class Settings {
  String dimension="minecraft:overworld";
  boolean centerSet=false;
  double x,y,z,radius=10,height=5;
  int duration=60;
  boolean boundary=true;
  LinkedHashSet<String> teams=new LinkedHashSet<>();
 }
 private Settings config=new Settings();
 private final CaptureRules rules=new CaptureRules();
 private final ServerBossEvent bar=new ServerBossEvent(Component.literal("Capture"),BossEvent.BossBarColor.WHITE,BossEvent.BossBarOverlay.PROGRESS);
 private boolean active,paused,storageFault;
 private int ticks;
 private String lastOccupant,winner;
 private Path file;
 public CaptureMod(){NeoForge.EVENT_BUS.register(this);}
 @SubscribeEvent public void started(ServerStartedEvent e){
  file=e.getServer().getWorldPath(LevelResource.ROOT).resolve("capture.json");
  config=new Settings();storageFault=false;stop();rules.reset();winner=null;
  if(Files.exists(file))try{
   Settings loaded=GSON.fromJson(Files.readString(file),Settings.class);
   if(loaded==null||loaded.teams==null||loaded.dimension==null||!Double.isFinite(loaded.x)||!Double.isFinite(loaded.y)||!Double.isFinite(loaded.z)||!(loaded.radius>=1&&loaded.radius<=256)||!(loaded.height>=0&&loaded.height<=384)||loaded.duration<1||loaded.duration>86400)throw new IllegalArgumentException("Invalid settings");
   config=loaded;
  }catch(Exception ex){storageFault=true;LOG.error("Capture settings invalid",ex);}
 }
 private boolean save(){
  if(storageFault||file==null)return false;
  try{Path tmp=file.resolveSibling("capture.json.tmp");Files.writeString(tmp,GSON.toJson(config));Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING);return true;}
  catch(java.io.IOException ex){LOG.error("Unable to save capture settings",ex);return false;}
 }
 @SubscribeEvent public void stopping(ServerStoppingEvent e){stop();}
 private void stop(){active=false;paused=false;ticks=0;lastOccupant=null;bar.removeAllPlayers();}
 private int ok(CommandSourceStack s,String m){s.sendSuccess(()->Component.literal(m),false);return 1;}
 private int fail(CommandSourceStack s,String m){s.sendFailure(Component.literal(m));return 0;}
 private int edit(CommandSourceStack s,Runnable action){
  if(active)return fail(s,"Stop Capture before changing settings.");
  if(storageFault)return fail(s,"Fix capture.json and restart.");
  String old=GSON.toJson(config);action.run();
  if(!save()){config=GSON.fromJson(old,Settings.class);return fail(s,"Could not save settings; change reverted.");}
  rules.reset();winner=null;return ok(s,"Capture settings saved.");
 }
 private int center(CommandSourceStack s,Vec3 p){return edit(s,()->{config.x=p.x;config.y=p.y;config.z=p.z;config.dimension=s.getLevel().dimension().location().toString();config.centerSet=true;});}
 private int team(CommandSourceStack s,String name,boolean add){
  if(add&&s.getServer().getScoreboard().getPlayerTeam(name)==null)return fail(s,"Unknown scoreboard team: "+name);
  return edit(s,()->{if(add)config.teams.add(name);else config.teams.remove(name);});
 }
 private ServerLevel level(MinecraftServer server){for(ServerLevel l:server.getAllLevels())if(l.dimension().location().toString().equals(config.dimension))return l;return null;}
 private int start(CommandSourceStack s){
  if(active)return fail(s,"Capture is already running.");
  if(storageFault||!config.centerSet||level(s.getServer())==null||config.teams.size()<2)return fail(s,"Set a valid center and register at least two teams first.");
  for(String t:config.teams)if(s.getServer().getScoreboard().getPlayerTeam(t)==null)return fail(s,"Missing scoreboard team: "+t);
  rules.reset();winner=null;active=true;paused=false;ticks=0;lastOccupant=null;
  announce(s.getServer(),Component.literal("Capture has started!"));updateBar(s.getServer(),Set.of());return 1;
 }
 private int pause(CommandSourceStack s,boolean value){
  if(!active)return fail(s,"No active game.");
  paused=value;ticks=0;lastOccupant=null;updateBar(s.getServer(),occupants(s.getServer()));return ok(s,value?"Capture paused.":"Capture resumed.");
 }
 private String status(){return "Capture: "+(active?(paused?"paused":"running"):"stopped")+" | Center: "+(config.centerSet?config.dimension+" "+config.x+" "+config.y+" "+config.z:"unset")+" | Radius: "+config.radius+" | Height: ±"+config.height+" | Teams: "+config.teams+" | Boundary: "+config.boundary+" | Progress: "+rules.progress+"/"+config.duration+" | Owner: "+rules.owner+" | Winner: "+winner;}
 @SubscribeEvent public void commands(RegisterCommandsEvent e){
  e.getDispatcher().register(literal("minigames").requires(s->s.hasPermission(2))
   .then(literal("list").executes(c->ok(c.getSource(),"Available minigames: Capture")))
   .then(literal("status").executes(c->ok(c.getSource(),status())))
   .then(literal("stopall").executes(c->{stop();return ok(c.getSource(),"All minigames stopped.");})));
  e.getDispatcher().register(literal("capture").requires(s->s.hasPermission(2))
   .then(literal("center").executes(c->center(c.getSource(),c.getSource().getPosition())).then(argument("position",Vec3Argument.vec3()).executes(c->center(c.getSource(),Vec3Argument.getVec3(c,"position")))))
   .then(literal("radius").then(argument("blocks",DoubleArgumentType.doubleArg(1,256)).executes(c->edit(c.getSource(),()->config.radius=DoubleArgumentType.getDouble(c,"blocks")))))
   .then(literal("height").then(argument("blocks",DoubleArgumentType.doubleArg(0,384)).executes(c->edit(c.getSource(),()->config.height=DoubleArgumentType.getDouble(c,"blocks")))))
   .then(literal("duration").then(argument("seconds",IntegerArgumentType.integer(1,86400)).executes(c->edit(c.getSource(),()->config.duration=IntegerArgumentType.getInteger(c,"seconds")))))
   .then(literal("boundary").then(argument("enabled",BoolArgumentType.bool()).executes(c->edit(c.getSource(),()->config.boundary=BoolArgumentType.getBool(c,"enabled")))))
   .then(literal("team")
    .then(literal("add").then(argument("name",StringArgumentType.string()).suggests((c,b)->net.minecraft.commands.SharedSuggestionProvider.suggest(c.getSource().getServer().getScoreboard().getTeamNames(),b)).executes(c->team(c.getSource(),StringArgumentType.getString(c,"name"),true))))
    .then(literal("remove").then(argument("name",StringArgumentType.string()).suggests((c,b)->net.minecraft.commands.SharedSuggestionProvider.suggest(config.teams,b)).executes(c->team(c.getSource(),StringArgumentType.getString(c,"name"),false))))
    .then(literal("list").executes(c->ok(c.getSource(),"Teams: "+config.teams))))
   .then(literal("start").executes(c->start(c.getSource())))
   .then(literal("stop").executes(c->{stop();return ok(c.getSource(),"Capture stopped.");}))
   .then(literal("pause").executes(c->pause(c.getSource(),true)))
   .then(literal("resume").executes(c->pause(c.getSource(),false)))
   .then(literal("reset").executes(c->{rules.reset();winner=null;ticks=0;lastOccupant=null;if(active)updateBar(c.getSource().getServer(),occupants(c.getSource().getServer()));return ok(c.getSource(),"Capture progress reset.");}))
   .then(literal("status").executes(c->ok(c.getSource(),status()))));
 }
 private boolean participant(ServerPlayer p){return p.getTeam()!=null&&config.teams.contains(p.getTeam().getName());}
 private Set<String> occupants(MinecraftServer server){
  Set<String> result=new HashSet<>();ServerLevel l=level(server);if(l==null)return result;
  for(ServerPlayer p:l.players()){
   if(!participant(p)||!p.isAlive()||p.isSpectator())continue;
   double dx=p.getX()-config.x,dz=p.getZ()-config.z;
   if(dx*dx+dz*dz<=config.radius*config.radius&&Math.abs(p.getY()-config.y)<=config.height)result.add(p.getTeam().getName());
  }return result;
 }
 private Component teamName(MinecraftServer server,String name){
  PlayerTeam t=name==null?null:server.getScoreboard().getPlayerTeam(name);
  return t==null?Component.literal(name==null?"Neutral":name):t.getDisplayName().copy().withStyle(t.getColor());
 }
 private BossEvent.BossBarColor color(MinecraftServer server,String name){
  PlayerTeam t=name==null?null:server.getScoreboard().getPlayerTeam(name);
  if(t==null)return BossEvent.BossBarColor.WHITE;
  return switch(t.getColor()){
   case RED,DARK_RED->BossEvent.BossBarColor.RED;
   case BLUE,DARK_BLUE,AQUA,DARK_AQUA->BossEvent.BossBarColor.BLUE;
   case GREEN,DARK_GREEN->BossEvent.BossBarColor.GREEN;
   case GOLD,YELLOW->BossEvent.BossBarColor.YELLOW;
   case DARK_PURPLE->BossEvent.BossBarColor.PURPLE;
   case LIGHT_PURPLE->BossEvent.BossBarColor.PINK;
   default->BossEvent.BossBarColor.WHITE;
  };
 }
 private void updateBar(MinecraftServer server,Set<String> occ){
  String state=paused?"Paused":occ.size()>1?"Contested":occ.isEmpty()?"Unoccupied":rules.progress>0&&!occ.contains(rules.owner)?"Neutralizing":"Capturing";
  String displayTeam=rules.progress==0&&occ.size()==1?occ.iterator().next():rules.owner;
  bar.setName(Component.literal("Capture — "+state+" | ").append(teamName(server,displayTeam)).append(Component.literal(" | "+rules.progress+"/"+config.duration+"s")));
  bar.setColor(color(server,displayTeam));bar.setProgress((float)rules.progress/config.duration);
  for(ServerPlayer p:List.copyOf(bar.getPlayers()))if(!participant(p)||server.getPlayerList().getPlayer(p.getUUID())!=p)bar.removePlayer(p);
  for(ServerPlayer p:server.getPlayerList().getPlayers())if(participant(p))bar.addPlayer(p);
 }
 private void announce(MinecraftServer server,Component message){for(ServerPlayer p:server.getPlayerList().getPlayers())if(participant(p))p.sendSystemMessage(message);}
 private void boundary(ServerLevel l){
  int count=Math.min(128,Math.max(24,(int)Math.ceil(config.radius*6)));
  for(int i=0;i<count;i++){double a=2*Math.PI*i/count;l.sendParticles(ParticleTypes.END_ROD,config.x+Math.cos(a)*config.radius,config.y+0.15,config.z+Math.sin(a)*config.radius,1,0,0,0,0);}
 }
 @SubscribeEvent public void tick(ServerTickEvent.Post e){
  if(!active)return;
  MinecraftServer server=e.getServer();ServerLevel l=level(server);
  if(l==null||config.teams.stream().anyMatch(t->server.getScoreboard().getPlayerTeam(t)==null)){announce(server,Component.literal("Capture aborted: arena or team missing."));stop();return;}
  Set<String> occ=occupants(server);String sole=occ.size()==1?occ.iterator().next():null;
  if(paused||sole==null){ticks=0;lastOccupant=null;}
  else{if(!sole.equals(lastOccupant)){ticks=0;lastOccupant=sole;}if(++ticks>=20){ticks=0;rules.advance(occ,config.duration);}}
  updateBar(server,occ);
  if(config.boundary&&server.getTickCount()%20==0)boundary(l);
  if(rules.progress>=config.duration){
   winner=rules.owner;Component msg=teamName(server,winner).copy().append(Component.literal(" wins Capture!").withStyle(ChatFormatting.GOLD));announce(server,msg);
   for(ServerPlayer p:server.getPlayerList().getPlayers())if(participant(p)){
    p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(msg));
    p.playNotifySound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE,SoundSource.PLAYERS,1f,1f);
   }stop();
  }
 }
}
