package com.watabou.pixeldungeon.coop.gameplay;

import com.watabou.noosa.TextureFilm;
import com.watabou.noosa.tweeners.AlphaTweener;
import com.watabou.pixeldungeon.Assets;
import com.watabou.pixeldungeon.sprites.CharSprite;

public class RemoteHeroSprite extends CharSprite {

	private static final int FRAME_WIDTH = 12;
	private static final int FRAME_HEIGHT = 15;

	public RemoteHeroSprite() {
		super();

		texture( Assets.ROGUE );
		TextureFilm frames = new TextureFilm( texture, FRAME_WIDTH, FRAME_HEIGHT );

		idle = new Animation( 1, true );
		idle.frames( frames, 0, 0, 0, 1, 0, 0, 1, 1 );

		run = new Animation( 20, true );
		run.frames( frames, 2, 3, 4, 5, 6, 7 );

		die = new Animation( 20, false );
		die.frames( frames, 8, 9, 10, 11, 12, 11 );

		attack = new Animation( 15, false );
		attack.frames( frames, 13, 14, 15, 0 );

		zap = attack.clone();

		operate = new Animation( 8, false );
		operate.frames( frames, 16, 17, 16, 17 );

		hardlight( 0.7f, 0.9f, 1.5f );
		play( idle );
	}

	@Override
	public void onComplete( Animation anim ) {
		super.onComplete( anim );
		if (anim == die && parent != null) {
			parent.add( new AlphaTweener( this, 0, 0.25f ) {
				@Override
				protected void onComplete() {
					RemoteHeroSprite.this.killAndErase();
					parent.erase( this );
				}
			} );
		}
	}
}
