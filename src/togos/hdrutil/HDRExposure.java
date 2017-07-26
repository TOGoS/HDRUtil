package togos.hdrutil;

public class HDRExposure
{
	public final int width, height;
	/** Sum of red, green, and blue over entire exposure for each pixel */ 
	public final float[] r, g, b;
	/** Virtual exposure time for each pixel */
	public final float[] e;
	
	public HDRExposure( int width, int height, float[] r, float[] g, float[] b, float[] e ) {
		assert Util.dimensionsSane(width, height);
		assert r.length >= width*height;
		assert g.length >= width*height;
		assert b.length >= width*height;
		assert e.length >= width*height;
		
		this.r = r;
		this.g = g;
		this.b = b;
		this.e = e;
		this.width = width;
		this.height = height;
	}
	
	public HDRExposure( int width, int height ) {
		this( width, height,
			new float[width*height],
			new float[width*height],
			new float[width*height],
			new float[width*height]
		);
	}
	
	public int getWidth() { return width; }
	public int getHeight() { return height; }
	
	public boolean isCompatible( HDRExposure other ) {
		return other.width == this.width && other.height == this.height;
	}
	
	public void add( HDRExposure other ) throws IncompatibleImageException {
		if( !isCompatible(other) ) throw new IncompatibleImageException("Cannot add(); images are incompatible sizes");
		
		BatchMath.add(e, other.e, e);
		BatchMath.add(r, other.r, r);
		BatchMath.add(g, other.g, g);
		BatchMath.add(b, other.b, b);
	}
	
	public HDRImage getImage() {
		HDRImage img = new HDRImage( width, height );
		img.load(this);
		return img;
	}
	
	public void clear() {
		for( int i=width*height-1; i>=0; --i ) {
			e[i] = r[i] = g[i] = b[i] = 0;
		}
	}

	public void multiply(double scale) {
		if( scale == 1 ) return;
		for( int i=width*height-1; i>=0; --i ) {
			r[i] *= scale;
			g[i] *= scale;
			b[i] *= scale;
			e[i] *= scale;
		}
	}
	
	public double getAverageExposure() {
		double sum = 0;
		for( int i=width*height-1; i>=0; --i ) {
			sum += e[i];
		}
		return sum / (width*height);
	}
}
