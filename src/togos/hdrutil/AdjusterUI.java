package togos.hdrutil;

import java.awt.Canvas;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;

import togos.hdrutil.effects.Bleed;

/*
 * TODO: Resettable background thread for recalculating image
 * TODO: Save, eXport, Merge from the UI
 */
public class AdjusterUI extends Canvas
{
	private static final long serialVersionUID = 1L;
	public static final String[] EMPTY_STRING_ARRAY = new String[0];
	
	protected int overlayTextMode = 1; 
	public String exportFilenamePrefix;
	public String[] extraStatusLines = EMPTY_STRING_ARRAY;
	HDRExposure hdrExposure;
	HDRImage hdrImage, hdrImage2;
	int[] argbBuf;
	BufferedImage bImg;
	float exposure = 1;
	float gamma = 2.2f;
	boolean dither = true;
	boolean bleed = false;
	
	class RecalculationThread extends Thread {
		public RecalculationThread() {
			super("AdjusterUI recalculation thread");
			setDaemon(true);
		}
		
		protected void kick( boolean realHard ) {
			if( !this.isAlive() ) start();
			else if( realHard ) interrupt();
			else synchronized(this) { notifyAll(); }
		}
		
		boolean needsRecalculation = true;
		
		public synchronized void settingsUpdated() {
			needsRecalculation = true;
			kick( true );
		}
		
		@Override public void run() {
			while( true ) {
				try {
					HDRExposure exp;
					synchronized( this ) {
						while( (exp = hdrExposure) == null || !needsRecalculation || !isShowing() ) {
							wait();
						}
						needsRecalculation = false; // Cuz we're doing it!
					}
					
					if( hdrImage == null || hdrImage.width != exp.width || hdrImage.height != exp.height ) {
						setPreferredSize( new Dimension(exp.getWidth(), exp.getHeight()));
						bImg = new BufferedImage( exp.getWidth(), exp.getHeight(), BufferedImage.TYPE_INT_ARGB );
						argbBuf = new int[exp.getWidth()*exp.getHeight()];
						hdrImage = exp.getImage();
						hdrExposure = exp;
					}
					
					hdrImage.load(hdrExposure);
					hdrImage.multiply(exposure);
					hdrImage.exponentiate( 1/gamma );
					
					if( bleed ) {
						if( hdrImage2 == null || hdrImage2.width != hdrImage.width || hdrImage2.height != hdrImage.height ) {
							hdrImage2 = new HDRImage(hdrImage.width, hdrImage.height);
						}
						Bleed.bleedXY(hdrImage, 0.8f, 0.01f, 0.5f, 0.5f, hdrImage2);
						
						HDRImage k = hdrImage;
						hdrImage = hdrImage2;
						hdrImage2 = k;
					}
					
					hdrImage.toArgb(argbBuf, dither);
					bImg.setRGB(0, 0, hdrImage.getWidth(), hdrImage.getHeight(), argbBuf, 0, hdrImage.getWidth());
					
					repaint();
				} catch( InterruptedException e ) {
					// Restart loop!
				}
			}
		}
	};
	
	protected final RecalculationThread recalculationThread = new RecalculationThread();
	
	public AdjusterUI() {
		super();
		setBackground(Color.BLACK);
		addComponentListener(new ComponentAdapter() {
			@Override public void componentShown(ComponentEvent e) {
				recalculationThread.kick( false );
			}
		});
		// componentShown won't always get called when we need it
		// to due to it actually being a parent component that gets
		// shown.  This seems to catch those cases:
		addHierarchyListener(new HierarchyListener() {
			@Override public void hierarchyChanged(HierarchyEvent e) {
				recalculationThread.kick( false );
			}
		});
		addKeyListener(new KeyAdapter() {
			protected double neg( double v, boolean negate ) {
				return negate ? 1.0/v : v;
			}
			
			@Override public void keyPressed(KeyEvent evt) {
				boolean negate = evt.isShiftDown();
				boolean controlled = evt.isControlDown();
				switch( evt.getKeyCode() ) {
				case KeyEvent.VK_G:
					gamma *= neg(controlled ? 1.125 : 1.5, negate);
					settingsUpdated();
					break;
				case KeyEvent.VK_E:
					exposure *= neg(controlled ? 1.125 : 1.5, negate);
					settingsUpdated();
					break;
				case KeyEvent.VK_D:
					dither ^= true;
					settingsUpdated();
					break;
				case KeyEvent.VK_B:
					bleed ^= true;
					settingsUpdated();
					break;
				case KeyEvent.VK_X:
					// Export
					try {
						exportImage();
					} catch( Exception e ) {
						System.err.println("Failed to export");
						e.printStackTrace(System.err);
					}
					break;
				case KeyEvent.VK_F1:
					++overlayTextMode;
					if( overlayTextMode > 2 ) overlayTextMode = 0;
					repaint();
					break;
				}
			}
		});
	}
	
	protected File getNewOutputFile( String prefix, String suffix ) {
		File f;
		for( int i=0; (f = new File(prefix+i+suffix)).exists(); ++i );
		return f;
	}
	
	protected File getNewImageExportFile() {
		return getNewOutputFile(exportFilenamePrefix+"-E"+exposure+"-G"+gamma+"-", ".png");
	}
	
	//// 
	
	
	// TODO: Exporting probably doesn't actually belong in the AWT thread
	protected void exportImage() throws IOException {
		BufferedImage img = bImg;
		if( img == null ) {
			throw new RuntimeException("No current image");
		}
		File out = getNewImageExportFile();
		System.err.println("Exporting to "+out.getPath()+"...");
		FileUtil.mkParentDirs(out);
		ImageIO.write(img, "png", out);
		System.err.println("Wrote "+out.getPath());
	}
	
	////
	
	protected void settingsUpdated() {
		recalculationThread.settingsUpdated();
	}
	
	protected void exposureUpdated() {
		settingsUpdated();
	}
	
	public synchronized void setExposure( HDRExposure exp ) {
		this.hdrExposure = exp;
		exposureUpdated();
	}
	
	@Override public void paint( Graphics g ) {
		if( hdrImage == null ) {
			g.setColor(Color.BLACK);
			g.fillRect(0, 0, getWidth(), getHeight());
		} else {
			int scale = 2;
			while( hdrImage.getWidth()*scale <= getWidth() && hdrImage.getHeight()*scale <= getHeight() ) {
				++scale;
			}
			--scale;
			
			int left  = (getWidth()  - hdrImage.getWidth() *scale) / 2;
			int top   = (getHeight() - hdrImage.getHeight()*scale) / 2;
			int right = hdrImage.getWidth() *scale + left;
			int bottom= hdrImage.getHeight()*scale + top  ;
			
			g.setColor(Color.BLACK);
			g.fillRect(0, 0, left, getHeight());
			g.fillRect(right, 0, getWidth()-right, getHeight());
			g.fillRect(left, 0, right - left, top);
			g.fillRect(left, bottom, right - left, getHeight() - bottom);
			
			g.drawImage(bImg, left, top, left+hdrImage.getWidth()*scale, top+hdrImage.getHeight()*scale, 0, 0, hdrImage.getWidth(), hdrImage.getHeight(), null);
		}

		int line = 1;
		
		if( overlayTextMode >= 1 ) {
			g.setColor(Color.WHITE);
			g.drawString(String.format("Exposure: %12.4f", exposure), 4, 16*line++ );
			g.drawString(String.format("Gamma:    %12.4f", gamma   ), 4, 16*line++ );
			g.drawString("Dithering: " +(dither ? "enabled" : "disabled"), 4, 16*line++ );
			
			if( extraStatusLines.length > 0 ) {
				++line;
				for( String text : extraStatusLines ) {
					g.drawString(text, 4, 16*line++ );
				}
			}
		}
		
		if( overlayTextMode == 1 ) {
			g.drawString("Hit F1 for help", 4, 16*line++ );
		}
		
		if( overlayTextMode >= 2 ) {
			++line;
			g.drawString("Keys:", 4, 16*line++);
			g.drawString("  F1 - toggle overlay text", 4, 16*line++);
			g.drawString("  X  - export image", 4, 16*line++);
			g.drawString("  E  - change exposure", 4, 16*line++);
			g.drawString("  G  - change gamma", 4, 16*line++);
			g.drawString("Hold shift to decrease exposure/gamma", 4, 16*line++);
			g.drawString("Hold control to change values more slowly", 4, 16*line++);
		}
	}
	
	@Override public void update( Graphics g ) {
		paint(g);
	}
	
	protected static HDRExposure addScaleyAndDestructively( HDRExposure a, HDRExposure b )
		throws IncompatibleImageException
	{
		if( a.width == b.width && a.height == b.height ) {
			a.add(b);
			return a;
		}
		
		if( !ExposureScaler.aspectRatioPreserved(a.width, a.height, b.width, b.height) ) {
			throw new IncompatibleImageException("Image aspect ratios mismatch; "+a.width+"x"+a.height+" vs "+b.width+"x"+b.height);
		}
		
		if( b.width > a.width ) {
			// Switch so that a is the larger one
			HDRExposure temp = b;
			b = a; a = temp;
		}
		
		a.add( ExposureScaler.scaleTo(b, a.width, a.height) );
		return a;
	}
	
	public static void main( String[] args ) throws Exception {
		String sceneName = null;
		HDRExposure sum = null;
		int avgSpp = 0;
		
		// Can be used when a dump is not loaded.
		boolean followTraceDump = true;
		int width = 240;
		int height = 160;
		double aspectRatio = width/height;
		double weight = 1;
		
		for( int i=0; i<args.length; ++i ) {
			String arg = args[i];
			if( "-follow-trace-dump".equals(arg) ) {
				followTraceDump = true;
			} else if( "-weight".equals(arg) ) {
				// Indicate weight for next image loaded
				weight = Double.parseDouble(args[++i]);
			} else if( "-ar".equals(arg) ) {
				String arString = args[++i];
				String[] arParts = arString.split(":");
				if( arParts.length == 1 ) {
					aspectRatio = Double.parseDouble(arString);
				} else {
					aspectRatio =
						Double.parseDouble(arParts[0]) /
						Double.parseDouble(arParts[1]);
				}
			} else if( arg.endsWith(".dump") ) {
				String dumpFilename = arg;
				File dumpFile = new File(dumpFilename);
				sceneName = dumpFile.getName();
				sceneName = sceneName.substring(0, sceneName.length()-5);
				System.err.println("Loading "+dumpFile+"...");
				HDRExposure exp = ChunkyDump.loadExposure(dumpFile);
				exp.multiply(weight);
				System.err.println("  -> "+exp.width+"x"+exp.height);
				if( exp.e.length > 0 ) {
					// Don't bother averaging; chunky dumps have the same spp everywhere
					avgSpp += (int)exp.e[0];
				}
				if( sum == null ) {
					sum = exp;
				} else {
					sum = addScaleyAndDestructively(sum,exp);
				}
			} else if( arg.endsWith(".rgbe") ) {
				File rgbeFile = new File(arg);
				sceneName = rgbeFile.getName();
				sceneName = sceneName.substring(0, sceneName.length()-5);
				System.err.println("Loading "+rgbeFile+"...");
				HDRExposure exp = RGBE.loadExposureFromRawRgbe(rgbeFile);
				exp.multiply(weight);
				System.err.println("  -> "+exp.width+"x"+exp.height);
				if( exp.e.length > 0 ) {
					// Don't bother averaging; RGBE files have the same spp everywhere
					avgSpp += (int)exp.e[0];
				}
				if( sum == null ) {
					sum = exp;
				} else {
					sum = addScaleyAndDestructively(sum,exp);
				}
			} else {
				System.err.println("Invalid argument: "+arg);
				System.exit(1);
			}
		}
		
		if( sum == null && followTraceDump ) {
			sum = new HDRExposure(width, height);
		}
		
		if( sum == null ) {
			System.err.println("No dumps specified!");
			System.exit(1);
			return;
		}
		
		System.err.println("Combined image is "+sum.width+"x"+sum.height);
		
		final Frame f = new Frame("Image adjuster");
		AdjusterUI adj = new AdjusterUI();
		adj.setPreferredSize(new Dimension(Math.max(sum.width, 24), Math.max(sum.height, 24)));
		adj.exportFilenamePrefix = sceneName + (avgSpp == 0 ? "" : "-"+avgSpp);
		adj.setExposure(sum);
		f.add(adj);
		f.pack();
		
		f.setVisible(true);
		adj.requestFocus();
		f.addWindowListener( new WindowAdapter() {
			@Override public void windowClosing( WindowEvent evt ) {
				f.dispose();
			}
		});
		
		if( followTraceDump ) {
			DataInputStream dis = new DataInputStream(System.in);
			long samplesRead = 0;
			boolean needsRecalc = false;
			try {
				while( true ) {
					float psX = dis.readFloat();
					float psY = dis.readFloat();
					// Ignore offset (3 floats), direction (3 floats), and frequency (1 float)
					for( int i=3+3+1-1; i>=0; --i ) dis.readFloat();
					
					float exposure = dis.readFloat();
					float red = dis.readFloat();
					float green = dis.readFloat();
					float blue = dis.readFloat();
					++samplesRead;
					
					int px = (int)(psX * sum.width);
					int py = (int)(psY * sum.height);
					if(
						px < 0 || px >= sum.width ||
						py < 0 || py >= sum.height
					) continue;
					
					int idx = sum.width * py + px;
					sum.r[idx] += red;
					sum.g[idx] += green;
					sum.b[idx] += blue;
					sum.e[idx] += exposure;
					needsRecalc = true;
					
					if( samplesRead % (sum.height * sum.width) == 0 ) {
						System.err.println(samplesRead+" samples read; updating");
						adj.setExposure(sum);
						needsRecalc = false;
					}
				}
			} catch( EOFException e ) {
				if( needsRecalc ) {
					adj.setExposure(sum);
					needsRecalc = false;
				}
				System.err.println("End of dump reached.  "+samplesRead+" samples read");
			}
		}
	}
}
