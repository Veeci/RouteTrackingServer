import { Anchor, type AnchorProps } from '@mantine/core';
import { createLink, type LinkComponent } from '@tanstack/react-router';
import { type AnchorHTMLAttributes, forwardRef } from 'react';

type MantineAnchorProps = Omit<AnchorProps, 'href'> & Omit<AnchorHTMLAttributes<HTMLAnchorElement>, 'color' | 'style'>;

const MantineAnchor = forwardRef<HTMLAnchorElement, MantineAnchorProps>((props, ref) => (
  <Anchor ref={ref} {...props} />
));
MantineAnchor.displayName = 'MantineAnchor';

const CreatedLink = createLink(MantineAnchor);

/**
 * A Mantine anchor that is a router link. `<Anchor component={Link}>` would lose the router's types, so a typo in a
 * path or a missing `params` would compile. `createLink` keeps them.
 */
export const AnchorLink: LinkComponent<typeof MantineAnchor> = (props) => <CreatedLink {...props} />;
