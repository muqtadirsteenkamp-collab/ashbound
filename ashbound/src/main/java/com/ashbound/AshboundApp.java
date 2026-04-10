package com.ashbound;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.util.*;

@SpringBootApplication
@RestController
@RequestMapping("/api/game")
@CrossOrigin(origins = "*", allowedHeaders = "*", allowCredentials = "false")
public class AshboundApp {

    public static void main(String[] args) {
        SpringApplication.run(AshboundApp.class, args);
    }

    // ════════════════════════════════════════════════════════
    //  SESSION KEYS
    // ════════════════════════════════════════════════════════

    private static final String GAME_KEY   = "GAME";
    private static final String COMBAT_KEY = "COMBAT";
    private static final String IN_COMBAT  = "IN_COMBAT";

    private GameState getGame(HttpSession s)     { return (GameState)   s.getAttribute(GAME_KEY);   }
    private CombatState getCombat(HttpSession s)  { return (CombatState) s.getAttribute(COMBAT_KEY); }
    private boolean inCombat(HttpSession s)       { Boolean b = (Boolean) s.getAttribute(IN_COMBAT); return b != null && b; }
    private void saveGame(HttpSession s, GameState g)    { s.setAttribute(GAME_KEY,   g); }
    private void saveCombat(HttpSession s, CombatState c){ s.setAttribute(COMBAT_KEY, c); s.setAttribute(IN_COMBAT, c != null); }

    // ════════════════════════════════════════════════════════
    //  ENDPOINTS
    // ════════════════════════════════════════════════════════

    @PostMapping("/start")
    public ResponseEntity<?> start(HttpSession session) {
        GameState g = new GameState();
        saveGame(session, g);
        saveCombat(session, null);
        return ok(stateSnap(g, false));
    }

    @GetMapping("/state")
    public ResponseEntity<?> state(HttpSession session) {
        GameState g = getGame(session);
        if (g == null) return ok(Map.of("hasGame", false, "inCombat", false));
        return ok(stateSnap(g, inCombat(session)));
    }

    @GetMapping("/scene/{n}")
    public ResponseEntity<?> scene(@PathVariable int n, HttpSession session) {
        GameState g = getGame(session);
        if (g == null) return err("No active game");
        SceneData s = SceneData.get(n, g);
        return s == null ? err("Invalid scene") : ok(s);
    }

    @PostMapping("/choice")
    public ResponseEntity<?> choice(@RequestBody ChoiceReq req, HttpSession session) {
        GameState g = getGame(session);
        if (g == null)        return err("No active game");
        if (inCombat(session)) return err("In combat");

        GameResult result = processChoice(req.scene, req.choice, g);
        if ("ERROR".equals(result.type)) return err(result.error);
        saveGame(session, g);

        if ("COMBAT".equals(result.type)) {
            saveCombat(session, initCombat(result.combatScene, g));
            return ok(map("type","COMBAT","combatScene",result.combatScene,"state",snapState(g)));
        }

        int next = g.currentScene;
        if (next == 4 || next == 8) {
            String sid = "SCENE_0" + next;
            saveCombat(session, initCombat(sid, g));
            return ok(map("type","COMBAT","narrative",nvl(result.narrative),"combatScene",sid,"state",snapState(g)));
        }

        return ok(map("type","STORY","narrative",nvl(result.narrative),"state",snapState(g)));
    }

    @PostMapping("/combat/move")
    public ResponseEntity<?> move(@RequestBody MoveReq req, HttpSession session) {
        GameState g   = getGame(session);
        CombatState c = getCombat(session);
        if (g == null || c == null) return err("Not in combat");

        CombatResult r = processTurn(req.move, c, g);
        saveCombat(session, c);

        if (r.fightOver && !"ENEMY_DOWN_PENDING".equals(r.outcome)) {
            resolveCombat(c.fightId, r.outcome, g);
            saveCombat(session, null);
            saveGame(session, g);
        }
        return ok(r);
    }

    @PostMapping("/combat/finish")
    public ResponseEntity<?> finish(@RequestBody FinishReq req, HttpSession session) {
        GameState g   = getGame(session);
        CombatState c = getCombat(session);
        if (c == null) return err("Not in combat");

        String outcome = req.lethal ? "WIN_LETHAL" : "WIN_NON_LETHAL";
        resolveCombat(c.fightId, outcome, g);
        saveCombat(session, null);
        saveGame(session, g);

        String narr = req.lethal ? "You finish it. Clean. Final." : "You step back. Let him breathe.";
        return ok(map("type","STORY","narrative",narr,"state",snapState(g)));
    }

    @GetMapping("/eulogy")
    public ResponseEntity<?> eulogy(HttpSession session) {
        GameState g = getGame(session);
        if (g == null) return err("No game");
        return ok(map("eulogy", EulogyEngine.generate(g), "momentLog", g.momentLog, "finalState", snapState(g)));
    }

    // ════════════════════════════════════════════════════════
    //  HELPERS
    // ════════════════════════════════════════════════════════

    private Map<String,Object> stateSnap(GameState g, boolean inCombat) {
        return map("hasGame", true, "inCombat", inCombat, "state", snapState(g));
    }

    private Map<String,Object> snapState(GameState g) {
        Map<String,Object> m = new HashMap<>();
        m.put("morality",      g.morality);
        m.put("fear",          g.fear);
        m.put("fatigue",       g.fatigue);
        m.put("moralityLabel", g.moralityLabel());
        m.put("fearLabel",     g.fearLabel());
        m.put("fatigueLabel",  g.fatigueLabel());
        m.put("currentScene",  g.currentScene);
        m.put("gameOver",      g.gameOver);
        return m;
    }

    private ResponseEntity<?> ok(Object body) {
        return ResponseEntity.ok(Map.of("data", body));
    }

    private ResponseEntity<?> err(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    private Map<String,Object> map(Object... kv) {
        Map<String,Object> m = new HashMap<>();
        for (int i = 0; i < kv.length - 1; i += 2) m.put(kv[i].toString(), kv[i+1]);
        return m;
    }

    private String nvl(String s) { return s != null ? s : ""; }

    // ════════════════════════════════════════════════════════
    //  GAME STATE
    // ════════════════════════════════════════════════════════

    public static class GameState implements Serializable {
        public int morality=0, fear=0, fatigue=0, currentScene=1;
        public boolean gameOver=false;
        public List<String> momentLog = new ArrayList<>();
        public String scene02Choice, scene05Choice, scene07Choice;
        public boolean wonScene04, wonScene06, wonScene08;

        public void mod(String stat, int d) {
            switch(stat){
                case "morality"->morality=clamp(morality+d);
                case "fear"    ->fear    =clamp(fear    +d);
                case "fatigue" ->fatigue =clamp(fatigue +d);
            }
        }
        public void log(String e){ if(e!=null&&!e.isBlank()) momentLog.add(e); }
        public void advance(){ currentScene++; if(currentScene>10) gameOver=true; }
        private int clamp(int v){ return Math.max(-100,Math.min(100,v)); }

        public String moralityLabel(){
            if(morality>50)  return "Trying to be good";
            if(morality>10)  return "Uncertain but trying";
            if(morality>-10) return "Neither";
            if(morality>-50) return "Compromised";
            return "Corrupted";
        }
        public String fearLabel(){
            if(fear>60)  return "Haunted";
            if(fear>20)  return "Unsettled";
            if(fear>-20) return "Composed";
            if(fear>-60) return "Numb";
            return "Empty";
        }
        public String fatigueLabel(){
            if(fatigue>60)  return "Broken";
            if(fatigue>20)  return "Worn";
            if(fatigue>-20) return "Steady";
            return "Sharp";
        }
    }

    // ════════════════════════════════════════════════════════
    //  COMBAT STATE
    // ════════════════════════════════════════════════════════

    public static class CombatState implements Serializable {
        public String fightId;
        public int playerHealth=100, enemyHealth=100, turn=1;
        public String lastPlayerMove;
        public boolean bodyOpened, playerExposed, fightOver, intimidateUsed;
        public int bodyOpenWindow, desperationModifier;
        public String outcome;
        public List<String> moves = new ArrayList<>();

        public CombatState(String fightId){ this.fightId=fightId; }
        public void addMove(String m){ if(!moves.contains(m)) moves.add(m); }
        public void openBody(){ bodyOpened=true; bodyOpenWindow=2; }
        public void closeBody(){ bodyOpened=false; bodyOpenWindow=0; }
        public void exposePlayer(){ playerExposed=true; }
        public void damagePlayer(int v){ playerHealth=Math.max(0,playerHealth-v); if(playerHealth<=0) endFight("LOSS"); }
        public void damageEnemy(int v){ enemyHealth=Math.max(0,enemyHealth-v); }
        public boolean enemyDown(){ return enemyHealth<=0; }
        public void endFight(String o){ fightOver=true; outcome=o; }
        public void nextTurn(){
            turn++;
            if(bodyOpened){ bodyOpenWindow--; if(bodyOpenWindow<=0) closeBody(); }
            playerExposed=false;
        }
        public boolean canUse(String m){
            if("HEADSHOT".equals(m)&&!bodyOpened) return false;
            if("INTIMIDATE".equals(m)&&intimidateUsed) return false;
            return moves.contains(m);
        }
        public List<String> available(){
            List<String> list=new ArrayList<>();
            for(String m:moves) if(canUse(m)) list.add(m);
            return list;
        }
    }

    // ════════════════════════════════════════════════════════
    //  GAME LOGIC
    // ════════════════════════════════════════════════════════

    static class GameResult {
        String type,narrative,combatScene,error;
        static GameResult story(String n){ GameResult r=new GameResult(); r.type="STORY"; r.narrative=n; return r; }
        static GameResult combat(String s){ GameResult r=new GameResult(); r.type="COMBAT"; r.combatScene=s; return r; }
        static GameResult err(String e){ GameResult r=new GameResult(); r.type="ERROR"; r.error=e; return r; }
    }

    static GameResult processChoice(int scene, int choice, GameState g){
        return switch(scene){
            case 1->s01(choice,g); case 2->s02(choice,g); case 3->s03(choice,g);
            case 5->s05(choice,g); case 6->s06(choice,g); case 7->s07(choice,g);
            case 9->s09(choice,g); default->GameResult.err("Invalid scene");
        };
    }

    static GameResult s01(int c,GameState g){
        switch(c){
            case 1->{g.mod("morality",-10);g.mod("fear",-5);}
            case 2->{g.mod("morality",-5);g.mod("fear",+10);}
            case 3->{g.mod("fatigue",+15);}
            default->{return GameResult.err("bad choice");}
        }
        g.advance();
        return GameResult.story("The fires die down. You start walking.");
    }

    static GameResult s02(int c,GameState g){
        String narr,log;
        switch(c){
            case 1->{g.mod("morality",+20);g.mod("fatigue",+20);g.mod("fear",+10);g.scene02Choice="HELPED";
                     log="He helped the one who stayed\nwhen he would not.";
                     narr="You bind the wound. Sit with him through it. You stay anyway.";}
            case 2->{g.mod("morality",-15);g.mod("fear",+15);g.scene02Choice="LEFT";
                     log="He walked past a dying brother.\nHe did not look back.";
                     narr="You walk. You don't look back.";}
            case 3->{g.mod("morality",-25);g.mod("fear",+20);g.mod("fatigue",+10);g.scene02Choice="ENDED";
                     log="He made a choice for another man\nthat was not his to make.\nHe told himself it was mercy.";
                     narr="It's fast. You walk before you can decide if it was right.";}
            case 4->{g.mod("morality",+10);g.mod("fear",+15);g.mod("fatigue",+15);g.scene02Choice="SAT";
                     log="He could not save him.\nHe stayed anyway.\nThat is either weakness or grace.\nHe never decided which.";
                     narr="You sit with him. No words worth saying. He goes in the early hours.";}
            default->{return GameResult.err("bad choice");}
        }
        g.log(log); g.advance();
        return GameResult.story(narr);
    }

    static GameResult s03(int c,GameState g){
        switch(c){
            case 1->{g.mod("morality",+10);g.mod("fear",-10);g.mod("fatigue",+10);}
            case 2->{g.mod("morality",-5);}
            case 3->{g.mod("morality",-20);g.mod("fear",+15);}
            case 4->{g.mod("fatigue",+25);g.mod("morality",+5);}
            default->{return GameResult.err("bad choice");}
        }
        g.advance();
        return GameResult.story("You leave the village behind.");
    }

    static GameResult s05(int c,GameState g){
        switch(c){
            case 1->{g.mod("fatigue",-30);g.mod("fear",-10);g.mod("morality",+10);g.scene05Choice="STAYED";}
            case 2->{g.mod("fatigue",-10);g.scene05Choice="GUARDED";}
            case 3->{g.mod("fatigue",-10);g.mod("morality",+5);g.scene05Choice="PRACTICAL";}
            case 4->{g.mod("fear",-15);g.mod("fatigue",-15);g.mod("morality",+15);g.scene05Choice="HONEST";
                     g.log("He told the truth to a stranger\nwho did not ask for it.\nShe did not turn him away.");}
            default->{return GameResult.err("bad choice");}
        }
        g.advance();
        return GameResult.story("You leave the waystation at dawn.");
    }

    static GameResult s06(int c,GameState g){
        switch(c){
            case 1->{return GameResult.combat("SCENE_06");}
            case 2->{g.mod("fear",+20);g.log("He saw what he could become\nand chose to walk away.\nHe is not sure if that was\nwisdom or cowardice.");
                     g.advance();return GameResult.story("You walk past him. His question stays longer than he does.");}
            case 3->{g.mod("fear",-15);g.mod("morality",+10);g.mod("fatigue",+10);
                     g.log("He sat with his own reflection\nand did not look away.");
                     g.advance();return GameResult.story("You sit. You listen. Something shifts.");}
            default->{return GameResult.err("bad choice");}
        }
    }

    static GameResult s07(int c,GameState g){
        String narr,log;
        switch(c){
            case 1->{g.mod("morality",-10);g.mod("fear",-20);g.scene07Choice="TRUTH";
                     log="When asked directly,\nhe told the truth.\nIt was not a good truth.";
                     narr="You say it plainly. She nods slowly. She leaves.";}
            case 2->{g.mod("morality",-15);g.mod("fear",+25);g.scene07Choice="LIE";
                     log="He was asked the hardest question.\nHe gave the answer\nthat let him sleep.";
                     narr="You construct it carefully. Maybe she believes you.";}
            case 3->{g.mod("morality",+5);g.mod("fear",+10);g.mod("fatigue",+10);g.scene07Choice="UNCERTAIN";
                     log="He admitted uncertainty\nto the one person\nwho deserved certainty.\nShe accepted it anyway.";
                     narr="You tell her you don't know. She stays. You talk for a while.";}
            case 4->{g.mod("fear",+30);g.scene07Choice="SILENCE";
                     log="He had no answer\nfor the woman who asked.\nThe silence said enough.";
                     narr="Nothing comes. She leaves without another word.";}
            default->{return GameResult.err("bad choice");}
        }
        g.log(log); g.advance();
        return GameResult.story(narr);
    }

    static GameResult s09(int c,GameState g){
        String narr,log;
        switch(c){
            case 1->{g.mod("fatigue",-20);
                     log="He found a quiet moment\nnear the end\nand let it be quiet.\nThat was enough.";
                     narr="You sit. You don't make it mean anything.";}
            case 2->{g.mod("fear",-15);g.mod("morality",+10);
                     log="At the end,\nhe thought about the ones\nhe could have saved.\nHe did not look away\nfrom that thought.";
                     narr="You think about the knight. You let yourself think about it fully.";}
            case 3->{g.mod("fear",+20);g.mod("morality",+5);
                     log="He spent his last quiet moment\nwith the thing he broke.\nHe still doesn't know\nif he was wrong.";
                     narr="To obey. You turn the words over. You still don't know if you were right.";}
            case 4->{g.mod("fear",-25);g.mod("morality",+15);
                     log="He prayed at the end.\nNot for forgiveness.\nJust to say the words\nout loud to something\nlarger than himself.";
                     narr="You say it anyway. Not asking for anything. It helps, a little.";}
            default->{return GameResult.err("bad choice");}
        }
        g.log(log); g.advance();
        return GameResult.story(narr);
    }

    static CombatState initCombat(String sid, GameState g){
        CombatState c=new CombatState(sid);
        switch(sid){
            case "SCENE_04"->{
                if("HELPED".equals(g.scene02Choice))      c.addMove("MEASURED_STRIKE");
                else if("LEFT".equals(g.scene02Choice))   c.addMove("DESPERATE_LUNGE");
                else if("SAT".equals(g.scene02Choice))    c.addMove("READ_HIM");
                else if("ENDED".equals(g.scene02Choice))  c.addMove("INTIMIDATE");
                else c.addMove("MEASURED_STRIKE");
                c.addMove("BODY_SHOT"); c.addMove("HEADSHOT");
            }
            case "SCENE_06"->{
                c.addMove("BODY_SHOT"); c.addMove("HEADSHOT");
                c.addMove("MEASURED_STRIKE"); c.addMove("DESPERATE_LUNGE");
                c.addMove("READ_HIM"); c.addMove("INTIMIDATE");
            }
            case "SCENE_08"->{
                if(g.morality>0){c.addMove("RESTRAINED_STRIKE");c.addMove("TALK_HIM_DOWN");}
                else c.addMove("END_THIS");
                c.addMove("BODY_SHOT"); c.addMove("HEADSHOT"); c.addMove("DESPERATE_LUNGE");
            }
        }
        return c;
    }

    static void resolveCombat(String fightId, String outcome, GameState g){
        boolean won=outcome.startsWith("WIN"), lethal="WIN_LETHAL".equals(outcome);
        switch(fightId){
            case "SCENE_04"->{
                g.wonScene04=won;
                if(won&&lethal){g.mod("morality",-20);g.log("He put down the man\nwho came to hold him accountable.");}
                else if(won){g.mod("morality",+10);g.log("He spared the one\nwho came for justice.\nHe is still unsure why.");}
                else{g.mod("fatigue",+40);g.mod("fear",+20);g.log("He was bested by the man\nhe used to be.");}
            }
            case "SCENE_06"->{
                g.wonScene06=won;
                if(won&&lethal){g.mod("morality",-15);g.log("He fought the hollow man and won.\nHe checked his own reflection after.");}
                else if(won){g.mod("morality",+10);g.log("He fought the hollow man and won.\nHe left him breathing.");}
                else{g.mod("fear",+25);g.mod("fatigue",+30);g.log("The hollow man bested him\nand chose to let him live.");}
            }
            case "SCENE_08"->{
                g.wonScene08=won;
                if(won&&lethal){g.mod("morality",-20);g.log("He killed the grief\nbefore it could finish\nasking its question.");}
                else if(won){g.mod("morality",+25);g.log("He put down his sword\nwhen he didn't have to.\nThe man wept.\nSo did he, after.");}
                else{g.mod("fatigue",+50);g.mod("fear",+30);g.log("He almost didn't survive\nthe weight of what he left behind.");}
            }
        }
        g.advance();
    }

    // ════════════════════════════════════════════════════════
    //  COMBAT ENGINE
    // ════════════════════════════════════════════════════════

    public static class CombatResult {
        public String move,breakdown,outcome,enemyMove,enemyBreakdown;
        public boolean success,bodyOpen,fightOver;
        public int playerHealth,enemyHealth;
        public List<String> availableMoves;
    }

    static CombatResult processTurn(String moveName, CombatState c, GameState gs){
        CombatResult r=new CombatResult();
        r.move=moveName;

        int chain=0;
        if(c.lastPlayerMove!=null) chain=chainMod(c.lastPlayerMove,moveName);
        if(c.playerExposed) chain-=20;

        if("READ_HIM".equals(moveName)){
            r.breakdown="You study him carefully.\nNext strike: +20%";
            r.success=false;
        } else {
            int base=("HEADSHOT".equals(moveName)&&!c.bodyOpened)?30:baseChance(moveName);
            int fear=fearMod(gs.fear), fat=fatigueMod(gs.fatigue);
            int fin=Math.max(5,Math.min(95,base+fear+fat+chain));
            int rolled=new Random().nextInt(100)+1;
            r.success=rolled<=fin;
            StringBuilder sb=new StringBuilder("Calculating...\n\n");
            sb.append(String.format("Base:    %d%%\n",base));
            if(fear!=0)  sb.append(String.format("Fear:    %+d%%\n",fear));
            if(fat!=0)   sb.append(String.format("Fatigue: %+d%%\n",fat));
            if(chain!=0) sb.append(String.format("Chain:   %+d%%\n",chain));
            sb.append(String.format("\nFinal:   %d%%\n\n→ %s",fin,r.success?"HIT":"MISS"));
            r.breakdown=sb.toString();
        }

        applyEffects(moveName,r.success,c);

        if(!c.fightOver){
            String em=enemyMove(c); int eb=55+(c.fightId.equals("SCENE_08")?c.desperationModifier:0);
            int ef=Math.max(5,Math.min(95,eb)); boolean eh=new Random().nextInt(100)+1<=ef;
            r.enemyMove=em; r.enemyBreakdown="Enemy "+em+" → "+(eh?"HIT":"MISS");
            if(eh) c.damagePlayer(c.playerExposed?35:20);
        }

        r.playerHealth=c.playerHealth; r.enemyHealth=c.enemyHealth;
        r.bodyOpen=c.bodyOpened; r.fightOver=c.fightOver; r.outcome=c.outcome;
        r.availableMoves=c.available();
        c.lastPlayerMove=r.success?moveName:moveName+"_MISS";
        c.nextTurn();
        return r;
    }

    static void applyEffects(String move, boolean hit, CombatState c){
        if(!hit){
            if("DESPERATE_LUNGE".equals(move)) c.exposePlayer();
            if("SCENE_08".equals(c.fightId))   c.desperationModifier=Math.min(c.desperationModifier+10,40);
            return;
        }
        switch(move){
            case "BODY_SHOT"         ->{c.damageEnemy(15);c.openBody();}
            case "MEASURED_STRIKE"   -> c.damageEnemy(20);
            case "HEADSHOT"          ->{c.damageEnemy(100);c.closeBody();c.endFight("ENEMY_DOWN_PENDING");}
            case "END_THIS"          ->{c.damageEnemy(100);c.endFight("WIN_LETHAL");}
            case "TALK_HIM_DOWN"     -> c.endFight("WIN_NON_LETHAL");
            case "DESPERATE_LUNGE"   -> c.damageEnemy(40);
            case "INTIMIDATE"        -> c.intimidateUsed=true;
            case "RESTRAINED_STRIKE" -> c.damageEnemy(10);
            default                  -> c.damageEnemy(15);
        }
        if(c.enemyDown()&&!c.fightOver) c.endFight("ENEMY_DOWN_PENDING");
    }

    static String enemyMove(CombatState c){
        return switch(c.fightId){
            case "SCENE_04"->c.bodyOpened?"HEADSHOT":"BODY_SHOT";
            case "SCENE_06"->"MIRROR_STRIKE";
            default->"STRIKE";
        };
    }

    static int chainMod(String p,String c){
        if("BODY_SHOT".equals(p)&&"HEADSHOT".equals(c)) return 25;
        if("READ_HIM".equals(p)) return 20;
        if(p.endsWith("_MISS")&&p.startsWith("DESPERATE")) return -20;
        if("MEASURED_STRIKE".equals(p)&&"BODY_SHOT".equals(c)) return 15;
        return 0;
    }

    static int baseChance(String m){
        return switch(m){
            case "BODY_SHOT"->60; case "MEASURED_STRIKE"->65; case "DESPERATE_LUNGE"->75;
            case "HEADSHOT"->80; case "END_THIS"->80; case "RESTRAINED_STRIKE"->60;
            case "TALK_HIM_DOWN"->70; case "INTIMIDATE"->55; default->60;
        };
    }

    static int fearMod(int f){
        if(f>60)return -20;if(f>40)return -15;if(f>20)return -10;if(f>0)return -5;
        return f<-40?5:0;
    }

    static int fatigueMod(int f){
        if(f>60)return -20;if(f>40)return -15;if(f>20)return -10;if(f>0)return -5;
        return f<-20?5:0;
    }

    // ════════════════════════════════════════════════════════
    //  EULOGY ENGINE
    // ════════════════════════════════════════════════════════

    static class EulogyEngine {
        static String generate(GameState s){
            StringBuilder e=new StringBuilder();
            e.append(opening(s.morality)).append("\n\n");
            String fl=fearLine(s.fear);
            if(fl!=null) e.append(fl).append("\n\n");
            e.append("—\n\n");
            if(s.momentLog.isEmpty()){
                e.append("He left no moments worth recording.\nThat is its own kind of answer.\n");
            } else {
                for(String m:s.momentLog) e.append(m.trim()).append("\n\n");
            }
            e.append("—\n\n").append(closing(s.morality));
            return e.toString().trim();
        }
        static String opening(int m){
            if(m>50)  return "He was not a good man.\nBut he was trying to become one.\nThat counts for something.\nIt has to.";
            if(m>10)  return "He lived in the space between\nwhat he was and what he wanted to be.\nMost people do.\nFew admit it.";
            if(m>-10) return "He was neither redeemed nor lost.\nHe was just a man\nwho made choices\nand lived with them\nuntil he didn't.";
            if(m>-50) return "He did what he had to do.\nThat's what he would have said.\nWhether it was true\nis not for us to know.";
            return "He became something\nhe probably didn't intend.\nMost people don't intend it.\nThat's the thing about becoming.";
        }
        static String fearLine(int f){
            if(f>60)  return "He was afraid for most of it.\nHe kept going anyway.\nThat is its own kind of courage.";
            if(f<-40) return "He stopped being afraid somewhere along the way.\nWhether that was peace or numbness\nonly he would have known.";
            return null;
        }
        static String closing(int m){
            if(m>50)  return "He earned nothing.\nHe tried for everything.\nThat gap is where most lives are lived.";
            if(m>10)  return "He was better at the end than the beginning.\nNot enough. But something.";
            if(m>-10) return "He did what you could.\nSo did everyone else.\nSo do we all.";
            if(m>-50) return "He survived.\nFor a while.\nThat was what he wanted.\nHe got it.";
            return "He broke his oath to live.\nHe lived.\nMake of that what you will.";
        }
    }

    // ════════════════════════════════════════════════════════
    //  SCENE DATA
    // ════════════════════════════════════════════════════════

    public static class SceneData {
        public int sceneNumber;
        public String title, text, image;
        public String[] choices;
        public boolean isCombat;

        SceneData(int n,String title,String text,String image,String[] choices,boolean combat){
            this.sceneNumber=n;this.title=title;this.text=text;
            this.image=image;this.choices=choices;this.isCombat=combat;
        }

        public static SceneData get(int n, GameState st){
            return switch(n){
                case 1->new SceneData(1,"The Aftermath",
                    "The fires are dying.\n\nYou can still hear them — the ones who stayed.\nSome aren't screaming anymore.\n\nYour sword is clean. That's the thing that keeps hitting you.\nYour sword is clean because you never turned back around.\n\nA knight's oath is simple:\nTo obey. Always. Without question.\n\nYou broke it. Quietly. In the dark.\nAnd you are alive because of it.\n\nWhat do you do with that?",
                    "images/scene01.png",
                    new String[]{"Accept it. You survived. That's enough.","Tell yourself it was the right call.","Stand here until you understand what you did."},false);
                case 2->new SceneData(2,"The Wounded Knight",
                    "He doesn't speak at first.\n\nJust looks at you the way people look at something they don't have a word for yet.\n\nThen:\n\n\"You left.\"\n\nNot an accusation. Almost like he's confirming something he already knew.\n\nHis hand is pressed against his side.\nThe blood is dark. Too dark.\nHe won't make it to a village.\n\nHe's looking at you like the next thing you do will explain everything.",
                    "images/scene02.png",
                    new String[]{"Help him. Bind the wound. Stay with him.","Leave without a word.","End it. Spare him the hours.","Sit with him. No healing. Just presence."},false);
                case 3->new SceneData(3,"The Village",
                    "They go quiet when you enter.\n\nA child gets pulled inside. A door closes. Then another.\n\nThe village elder steps forward. Old. Steady.\n\n\"We don't want trouble.\"\n\nHe means: we know what you are.\nWe don't know what you've done.\nWe'd like to keep it that way.\n\nYou're tired. You're hungry.\nYou could take what you need.\nOr you could earn it.\nOr you could keep walking.",
                    "images/scene03.png",
                    new String[]{"Ask politely. Offer to work for food.","Say nothing. Take what you need and leave coin.","Use their fear. Take what you need.","Keep walking. You don't deserve rest yet."},false);
                case 4->new SceneData(4,"The Oath-Keeper",
                    "He steps out of the treeline.\n\nFull armour. Visor up.\nHe wants you to see his face.\n\n\"I've been looking for you since the morning after.\"\n\nHe draws his sword slowly. Not a threat. A formality.\n\n\"You broke the oath. You know what that means.\"\n\nHe settles into his stance.\n\n\"Don't make this ugly.\"",
                    "images/scene04.png",new String[]{},true);
                case 5->new SceneData(5,"The Offer",
                    "She doesn't flinch when you walk in.\n\nJust looks you over the way a carpenter looks at warped wood — not judgement, just assessment.\n\n\"You look like you haven't slept in something that wasn't dirt.\"\n\nShe sets a bowl down without asking.\n\n\"Eat. The room in the back is empty.\nI don't need your name and I don't want your story.\"\n\nShe means it. That's the strangest part.",
                    "images/scene05.png",
                    new String[]{"Stay. Rest properly. Let yourself be human.","Eat but don't sleep. Keep your guard up.","Ask her about the road ahead. Stay practical.","Tell her something true about yourself."},false);
                case 6->new SceneData(6,"The Hollow Man",
                    "He's sitting in the middle of the road.\n\nNot blocking it on purpose. Just... there.\nLike he forgot roads were for going somewhere.\n\n\"Knight.\"\n\nNot a question. Not mockery. Just the word.\n\n\"You've got that look. The one where you've done something you can't undo and you're still deciding if it matters.\"\n\nHe stands slowly.\n\n\"I had that look once.\"",
                    "images/scene06.png",
                    new String[]{"Fight him. You don't want to become him.","Walk past him. He's not your problem.","Talk to him. Actually listen."},false);
                case 7->new SceneData(7,"The Reckoning",
                    "She's been following you for two days.\n\nYou knew. You let her catch up.\n\nShe's not armed. She's not angry. She's worse than both.\n\n\"I saw you go,\" she says. \"That night. I saw your face when you made the decision.\"\n\n\"I'm not here to report you. I'm not here to punish you.\"\n\nA long pause.\n\n\"When you chose to leave — did you think about anyone else? Even for a second?\"",
                    "images/scene07.png",
                    new String[]{"\"No. I only thought about myself.\"","\"I thought about everyone. That's why I left.\"","\"I don't know. I've been trying to figure that out.\"","Say nothing. Let the silence answer."},false);
                case 8->new SceneData(8,"The Familiar Face",
                    "You almost don't recognise him.\n\nBut he recognises you immediately.\n\nHe's been carrying it in his face — that specific grief that has somewhere to point now.\n\nHe has a blade. Not a knight's blade. A working knife.\nThe kind you use for hard things.\n\n\"You know who I am.\"\n\nYou do.\n\n\"I'm not going to ask you anything. I already know everything.\"\n\nHe moves forward.\n\nThis is not a duel.\nThis is a consequence.",
                    "images/scene08.png",new String[]{},true);
                case 9->new SceneData(9,"The Last Still Moment",
                    "Nothing is happening.\n\nThat's the strange part.\n\nNo one is coming. No decision is waiting.\nThe road ahead is there but you haven't taken a step yet.\n\nYou're just here.\n\nAlive. Still. After everything.\n\nThe sun is doing something with the light that makes the world look almost like it used to.\n\nWhat do you do with a moment like this?",
                    "images/scene09.png",
                    new String[]{"Let it be. Just sit. Don't make it mean something.","Think about the wounded knight.","Think about the oath.","Pray. Even though you're not sure anyone is listening."},false);
                case 10->new SceneData(10,"Death",
                    "And then it's over.\n\nNot with ceremony.\nNot with justice.\nJust over.\n\nThe way things end\nwhen they have run their course.",
                    "images/scene10.png",new String[]{},false);
                default->null;
            };
        }
    }

    // ════════════════════════════════════════════════════════
    //  REQUEST MODELS
    // ════════════════════════════════════════════════════════

    @JsonIgnoreProperties(ignoreUnknown=true)
    public static class ChoiceReq { public int scene, choice; }
    @JsonIgnoreProperties(ignoreUnknown=true)
    public static class MoveReq   { public String move; }
    @JsonIgnoreProperties(ignoreUnknown=true)
    public static class FinishReq { public boolean lethal; }
}
