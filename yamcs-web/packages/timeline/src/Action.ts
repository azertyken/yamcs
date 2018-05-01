export type ActionType = 'click'
  | 'contextmenu' // todo remove? just use click?
  | 'grabstart'
  | 'grabmove'
  | 'grabend'
  | 'mouseenter'
  | 'mousemove'
  | 'mouseleave';

/**
 * An action as generated by the event handler.
 *
 * Actions are only generated when the contribution
 * has registered one or more interaction targets.
 *
 * Currently, actions are emitted to _all_ contributions,
 * which in turn implement specific behaviour on what to
 * do with the action.
 */
export interface Action {

  type: ActionType;

  /**
   * DOM element associated with this action.
   */
  target?: Element;

  clientX: number;
  clientY: number;

  /**
   * Whether the user is currently in the middle of doing any kind of grab action.
   * (pan, select, resize, drag).
   */
  grabbing: boolean;

  /**
   * Optional date associated with the event. For example
   * a click on a viewport location matches to a specific date.
   */
  date?: Date;
}
