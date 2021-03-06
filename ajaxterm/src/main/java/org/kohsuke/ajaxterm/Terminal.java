package org.kohsuke.ajaxterm;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static java.lang.Math.max;
import static java.lang.Math.min;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;

/**
 * Screen buffer.
 *
 * @author Kohsuke Kawaguchi
 */
public class Terminal {
    /**
     * This is typed as 'char' but it's not character that's stored.
     * The lower 8 bit is the character code, and upper 8 bit are back/fore color.
     *
     * 0xFBCC
     *   ^^~~ <- ASCII char code
     *   ||
     *   |+-- background color code (0:black, 1:blue, 2:red, 4:green, ....)
     *   |
     *   +--- foreground color code
     */
    public char[] scr;
    /**
     * Screen width and height.
     */
    public final int width,height;
    /**
     * Scroll region.
     */
    private int st,sb;
    /**
     * Current cursor position.
     */
    private int cx,cy;
    /**
     * Cursor back up position.
     */
    private int cx_bak,cy_bak;
    private boolean cl;
    /**
     * Set graphics rendition. This is the value that gets stored into the higher 8 bits of {@link #scr}
     *
     * @see #csi_m(int[])
     */
    private int sgr;
    private String buf; // TODO: switch to StringBuilder
    private String outbuf;
    /**
     * The HTML that we returned from {@link #dumpHtml(boolean,int)} the last time.
     */
    private String last_html;
    /**
     * The value of {@link #timestamp} when we computed {@link #last_html}
     */
    private int last_html_timestamp;

    /**
     * Unique counter that increases as the screen changes.
     *
     * Don't start by 0 as that's the typical client's initial value.
     */
    private int timestamp = 1000;

    /**
     * True if the cursor should be displayed.
     */
    public boolean showCursor;

    private String cssClass;

    public Terminal(int width, int height) {
        this.width = width;
        this.height = height;
        reset();
    }

    /**
     * Sets additional CSS classes for the terminal element.
     */
    public void setCssClass(String cssClass) {
        this.cssClass = cssClass;
    }

    public int getCx() {
        return cx;
    }

    public int getCy() {
        return cy;
    }

    @Esc("\u001Bc")
    public void reset() {
        scr = new char[width*height];
        Arrays.fill(scr,EMPTY_CH);
        st = 0;
        sb = height-1;
        cx_bak = cx = 0;
        cy_bak = cy = 0;
        cl = false;
        sgr = 0x700;
        showCursor = true;
        buf = outbuf = last_html = "";
        timestamp += 1000;
    }

    private int $(int y, int x) {
        return y*width+x;
    }

    public String peek(int y1, int y2) {
        return peek(y1,0,y2,width);
    }

    public String peek(int y1, int x1, int y2, int x2) {
        int s = $(y1,x1);
        return new String(scr,s,$(y2,x2)-s);
    }

    public void poke(int y, int x, String s) {
        // TODO: i18n
        char[] chars = s.toCharArray();
        int destPos = $(y, x);
        System.arraycopy(chars,0,scr, destPos, min(chars.length, scr.length - destPos));
    }

    public void poke(int y, String s) {
        poke(y,0,s);
    }

    public void zero(int y1, int x1, int y2, int x2) {
        int e = $(y2,x2);
        for( int i= $(y1,x1); i<e; i++ )
            scr[i] = EMPTY_CH;
    }

    public void zero(int y1, int y2) {
        zero(y1, 0, y2, width);
    }

    /**
     * Scroll the (y1+1,y2) region up one line to (y1,y2-1) 
     */
    public void scrollUp(int y1, int y2) {
        poke(y1,peek(y1+1,y2));
        zero(y2,y2);
    }

    public void scrollDown(int y1, int y2) {
        poke(y1+1,peek(y1,y2-1));
        zero(y1,y1);
    }

    public void scrollRight(int y, int x) {
        poke(y,x+1,peek(y,x,y,width));
        zero(y,x,y,x);
    }

    @Esc({"\n","\u000B","\u000C"})
    public void cursorDown() {
        if(cy>=st && cy<=sb) {
            cl=false;
            int q = (cy+1)/(sb+1);
            if(q!=0) {
                scrollUp(st,sb);
                cy = sb;
            } else {
                cy = (cy+1)%(sb+1);
            }
        }
    }

    public void cursorRight() {
        if((cx+1)>=width)
            cl=true;
        else
            cx = (cx+1)%width;
    }

    public void echo(char c) {
        if(cl) {
            cursorDown();
            cx = 0;
        }
        scr[$(cy,cx)] = (char)(sgr|c);
        cursorRight();
    }

    public void escape() {
        if(buf.length()>32) {
            // error
            if(LOGGER.isLoggable(Level.FINE))
                LOGGER.fine("Unhandled escape sequence: "+buf.replaceAll("\u001B","<ESC>"));
            buf = "";
            return;
        }
        EscapeSequence es = ESCAPE_SEQUENCES.get(buf);
        if(es!=null) {
            es.handle(this, buf,null);
            buf = "";
            return;
        }

        for (Entry<Pattern, EscapeSequence> ent : REGEXP_ESCAPE_SEQUENCES.entrySet()) {
            Matcher m = ent.getKey().matcher(buf);
            if(m.matches()) {
                ent.getValue().handle(this, buf,m);
                buf = "";
                return;
            }
        }
    }

    /**
     * Receives the output from the forked process into the terminal.
     */
    public void write(String s) {
        timestamp++;
        if (LOGGER.isLoggable(Level.FINEST))
            LOGGER.finest("Received: "+s);
        for( int i=0; i<s.length(); i++ ) {
            char ch = s.charAt(i);
            if(buf.length()>0 || ESCAPE_SEQUENCES.containsKey(""+ch)) {
                buf += ch;
                escape();
            } else
            if(ch=='\u001B') {
                buf += ch;
            } else {
                echo(ch);
            }
        }
    }

    public String read() {
        String b = outbuf;
        outbuf = null;
        return b;
    }

    public String dump() {
        StringBuilder buf = new StringBuilder(scr.length);
        for (char ch : scr)
            buf.append((char) (ch&0xFF));
        return buf.toString();
    }

    public String dumpLatin1() {
        StringBuilder buf = new StringBuilder(scr.length);
        int i=0;
        for (char ch : scr) {
            buf.append(LATEN1_TABLE.charAt((ch&0xFF)));
            if (++i%width==0)
                buf.append('\n');
        }
        return buf.toString();
    }

    private int pack(int fg, int bg, boolean cursor) {
        return (cursor?1<<8:0)+(fg<<4)+(bg);
    }

    /**
     * @param color
     *      If we want the color coded output. It'll make the response bit bigger.
     * @param clientTimestamp
     *      The value of {@link ScreenImage#timestamp} that the client currently has.
     *      This information is used to avoid unnecessary screen refresh.
     */
    public ScreenImage dumpHtml(boolean color, int clientTimestamp) {
        if (timestamp==clientTimestamp) // our screen hasn't changed
            return new ScreenImage(clientTimestamp, NO_CHANGE, this);

        StringBuilder r = new StringBuilder(cx*cy*2);
        r.append("<pre class='term ");
        if (cssClass!=null)
            r.append(cssClass);
        r.append("'>");

        int currentStatus = -1;

        int total = height * width;
        for( int i=0; i< total; i++) {
            int q = scr[i]/256;

            int bg,fg;
            if(color) {
                bg = q/16;
                fg = q%16;
            } else {
                bg = 1;
                fg = 7;
            }
            boolean cursor = $(cy,cx)==i;
            int p = pack(fg,bg,cursor);
            if(currentStatus!=p) {// rendering status has changed
                currentStatus = p;
                if(i!=0)    r.append("</span>");
                r.append("<span class='f").append(fg).append(" b").append(bg);
                if (cursor) r.append(" cur");
                r.append("'>");
            }

            int c = scr[i]%256;
            switch (c) {
            case '<':   r.append("&lt;");break;
            case '&':   r.append("&amp;");break;
            default:    r.append(HTML_TABLE.charAt(c));break;
            }
            if((i+1)%width ==0)  r.append('\n');
        }

        r.append("</span></pre>");

        String str = r.toString();
        if(str.equals(last_html) && last_html_timestamp==clientTimestamp) {
            return new ScreenImage(clientTimestamp,NO_CHANGE,this);
        } else {
            last_html = str;
            last_html_timestamp = timestamp;
            return new ScreenImage(timestamp,str,this);
        }
    }

    @Esc({"\u0005","\u001B[c","\u001B[0c","\u001BZ"})
    public void esc_da() {
        outbuf = "\u001B[?6c";
    }

    /**
     * Backspace.
     */
    @Esc("\u0008")
    public void esc_0x08() {
        cx = max(0,cx-1);
    }

    /**
     * Tab.
     */
    @Esc("\u0009")
    public void esc_0x09() {
        cx = (((cx/8)+1)*8)%width;
    }

    /**
     * Carriage return
     */
    @Esc("\r")
    public void esc_0x0d() {
        cl=false;
        cx=0;
    }

    @Esc({"\u0000","\u0007","\u000E","\u000F","\u001B#8",
          "\u001B=","\u001B>","\u001B(0","\u001B(A",
          "\u001B(B","\u001B]R","\u001BD","\u001BE","\u001BH",
          "\u001BN","\u001BO","\u001Ba","\u001Bn","\u001Bo"})
    public void noOp() {
    }

    @Esc("\u001B7")
    public void saveCursor() {
        cx_bak = cx;
        cy_bak = cy;
    }

    @Esc("\u001B8")
    public void restoreCursor() {
        cx = cx_bak;
        cy = cy_bak;
    }

    @Esc("\u001BM")
    public void escRi() {
        cy = max(st,cy-1);
        if(cy==st)
            scrollDown(st,sb);
    }

    public void csi_A(int[] i) {
        cy = max(st,cy-defaultsTo(i,1));
    }

    public void csi_B(int[] i) {
        cy = min(sb,cy+defaultsTo(i,1));
    }

    public void csi_C(int[] i) {
        cx = min(width-1,cx+defaultsTo(i,1));
        cl = false;
    }

    public void csi_D(int[] i) {
        cx = max(0,cx-defaultsTo(i,1));
        cl = false;
    }

    /**
     * args[0], otherwise defaults to 'defaultValue'
     */
    private int defaultsTo(int[] args, int defaultValue) {
        return (args.length==0) ? defaultValue : args[0];
    }

    public void csi_E(int[] i) {
        csi_B(i);
        cx = 0;
        cl = false;
    }

    public void csi_F(int[] i) {
        csi_A(i);
        cx = 0;
        cl = false;
    }

    public void csi_G(int[] i) {
        cx = min(width,i[0])-1;
    }

    public void csi_H(int[] i) {
        if(i.length<2)  i=new int[]{1,1};
        cx = min(width,i[1])-1;
        cy = min(height,i[0])-1;
        cl = false;
    }

    public void csi_J(int[] i) {
        switch (defaultsTo(i,0)) {
        case 0: zero(cy,cx,height,0);return;
        case 1: zero(0,0,cx,cy);return;
        case 2: zero(0,0,height,0);return;
        }
    }

    public void csi_K(int... i) {
        switch (defaultsTo(i,0)) {
        case 0: zero(cy,cx,cy,width);return;
        case 1: zero(cy,0,cy,cx);return;
        case 2: zero(cy,0,cy,width);return;
        }
    }

    /**
     * Insert lines.
     */
    public void csi_L(int[] args) {
        for(int i=0;i<defaultsTo(args,1);i++)
            if(cy<sb)
                scrollDown(cy,sb);
    }

    /**
     * Delete lines.
     */
    public void csi_M(int[] args) {
        if(cy>=st && cy<=sb)
            for(int i=0;i<defaultsTo(args,1);i++)
                scrollUp(cy,sb);
    }

    /**
     * Delete n chars
     */
    public void csi_P(int[] args) {
        int _cy=cy,_cx=cx;
        String end = peek(cy,cx,cy,width);
        csi_K(0);
        poke(_cy,_cx,end.substring(defaultsTo(args,1)));
    }

    public void csi_X(int[] args) {
        zero(cy,cx,cy,cx+args[0]);
    }

    public void csi_a(int[] args) {
        csi_C(args);
    }

    public void csi_c(int[] args) {
        // noop
    }

    public void csi_d(int[] args) {
        cy = min(height,args[0])-1;
    }

    public void csi_e(int[] args) {
        csi_B(args);
    }

    public void csi_f(int[] args) {
        csi_H(args);
    }

    public void csi_h(int[] args) {
        switch(args[0]) {
        case 25:
            showCursor = true;
            break;
        }
    }

    public void csi_l(int[] args) {
        switch(args[0]) {
        case 25:
            showCursor = false;
            break;
        }
    }

    public void csi_m(int[] args) {
        if (args.length==0) {
            sgr = 0x0700;
            return;
        }

        for( int n : args ) {
            if(n==0 || n==39 || n==49 || n==27)
                sgr =  0x0700;
            if(n==1)
                sgr |= 0x0800;
            if(n==7)
                sgr |= 0x7000;
            if(30<=n && n<=37)
                sgr = (sgr&0xF8FF)|((n-30)<<8);
            if(40<=n && n<=47)
                sgr = (sgr&0x0FFF)|((n-40)<<12);
       }
    }

    public void csi_r(int[] args) {
        if(args.length<2)   args=new int[]{0,height};
        st=min(height,args[0])-1;
        sb=min(height,args[1])-1;
        sb=max(sb,st);
    }

    public void csi_s(int[] args) {
        sb=max(sb,st);
        saveCursor();
    }

    public void csi_u(int[] args) {
        restoreCursor();
    }

    private static interface EscapeSequence {
        void handle(Terminal t, String s, Matcher m);
    }

    private static final EscapeSequence NONE = new EscapeSequence() {
        public void handle(Terminal t, String s, Matcher m) {
        }
    };

    private static final Map<String,EscapeSequence> ESCAPE_SEQUENCES = new HashMap<String,EscapeSequence>();

    private static final Map<Pattern,EscapeSequence> REGEXP_ESCAPE_SEQUENCES = new HashMap<Pattern,EscapeSequence>();

    private static abstract class CsiSequence {
        final int arg2; // ?

        protected CsiSequence(int arg2) {
            this.arg2 = arg2;
        }

        abstract void handle(Terminal t, int[] args);
    }

    private static final Map<Character,CsiSequence> CSI_SEQUENCE = new HashMap<Character,CsiSequence>();

    private static final String HTML_TABLE, LATEN1_TABLE;

    static {
        for( final Method m : Terminal.class.getMethods() ) {
            Esc esc = m.getAnnotation(Esc.class);
            if(esc!=null) {
                for( String s : esc.value() ) {
                    ESCAPE_SEQUENCES.put(s,new EscapeSequence() {
                        public void handle(Terminal t, String s, Matcher _) {
                            try {
                                m.invoke(t);
                            } catch (IllegalAccessException e) {
                                throw new IllegalAccessError();
                            } catch (InvocationTargetException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    });
                }
            }
            if(m.getName().startsWith("csi_") && m.getName().length()==5) {
                CSI_SEQUENCE.put(m.getName().charAt(4),new CsiSequence(1) {
                    void handle(Terminal t, int[] args) {
                        try {
                            m.invoke(t,new Object[]{args});
                        } catch (IllegalAccessException e) {
                            throw new IllegalAccessError();
                        } catch (InvocationTargetException e) {
                            throw new RuntimeException(e);
                        }
                    }
                });
            }
        }

        REGEXP_ESCAPE_SEQUENCES.put(
            Pattern.compile("\u001B\\[\\??([0-9;]*)([@ABCDEFGHJKLMPXacdefghlmnqrstu`])"),
            new EscapeSequence() {
                public void handle(Terminal t, String _, Matcher m) {
                    String s = m.group(1);
                    CsiSequence seq = CSI_SEQUENCE.get(m.group(2).charAt(0));
                    if(seq!=null) {
                        String[] tokens = s.split(";");
                        if (s.length()==0)
                            tokens = EMPTY_STRING_ARRAY;
                        int[] n = new int[tokens.length];
                        for (int i = 0; i < n.length; i++)
                            try {
                                n[i] = Integer.parseInt(tokens[i]);
                            } catch (NumberFormatException e) {
                                n[i] = 0;
                            }
                        seq.handle(t,n);
                    }
                }
            });
        REGEXP_ESCAPE_SEQUENCES.put(
            Pattern.compile("\u001C([^\u0007]+)\u0007"),
            NONE);

        CSI_SEQUENCE.put('@',new CsiSequence(1) {
            @Override
            void handle(Terminal t, int[] args) {
                for( int i=0; i<args[0]; i++)
                    t.scrollRight(t.cy,t.cx);
            }
        });

        StringBuffer html = new StringBuffer(256);
        StringBuffer lat1 = new StringBuffer(256);
        for( int i=0; i<256; i++) {
            if(i<32) {
                lat1.append(' ');
                if(i==0x0A)
                    html.append('\n');
                else
                    html.append('\u00A0'); // &nbsp;
            } else
            if(i<127 || 160<i) {
                lat1.append((char)i);
                html.append((char)i);
            } else {
                lat1.append('?');
                html.append('?');
            }
        }
        HTML_TABLE = html.toString();
        LATEN1_TABLE = lat1.toString();
    }

    private static final char EMPTY_CH = '\u0700'; // back=0,fore=7,char=0

    private static final Logger LOGGER = Logger.getLogger(Terminal.class.getName());

    private static final String NO_CHANGE = "<idem></idem>";
    private static final String[] EMPTY_STRING_ARRAY = new String[0];
}
