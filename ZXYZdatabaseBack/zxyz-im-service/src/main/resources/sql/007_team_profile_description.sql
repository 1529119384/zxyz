USE zxyz_im;

ALTER TABLE im_team
    ADD COLUMN description VARCHAR(500) NULL AFTER avatar;
