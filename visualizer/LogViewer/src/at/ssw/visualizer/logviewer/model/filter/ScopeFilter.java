package at.ssw.visualizer.logviewer.model.filter;

import at.ssw.visualizer.logviewer.model.LogLine;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 *
 * @author Alexander Stipsits
 */
class ScopeFilter implements Filter {

	private Pattern p;
	private boolean active = true;
	
	@Override
	public void setConstraints(Object ... constraints) {
        this.p = null;
		for(Object constraint : constraints) {
			setConstraint(constraint);
		}
	}
	
	private void setConstraint(Object constraint) {
		if(constraint instanceof String) {
			if(((String)constraint).trim().length() > 0) {
				this.p = Pattern.compile((String)constraint);
			}
			else {
				this.p = null;
			}
		}
	}

	@Override
	public boolean keep(LogLine line) {
		if(p == null) return true;
        
        if(line.getScope() == null) return false;
		
		Matcher matcher = p.matcher(line.getScope().getName());
		return matcher.find();
	}

	@Override
	public void setActive(boolean active) {
		this.active = active;
	}

	@Override
	public boolean isActive() {
		return active;
	}

}
